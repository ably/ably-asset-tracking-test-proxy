package com.ably.tracking.test

import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.plugins.websocket.cio.wsRaw
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.Url
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocketRaw
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameType
import io.ktor.websocket.close
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.net.ssl.SSLSocketFactory
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

// TODO If we want to be able to support multiple fault simulations at the same time, then we need to not use static values here. I don’t think it’s a pressing need, though, since we currently don’t run the SDK tests in parallel
private const val PROXY_HOST = "localhost"
private const val PROXY_PORT = 13579
private const val REALTIME_HOST = "realtime.ably.io"
private const val REALTIME_PORT = 443

/**
 * A local proxy that can be used to intercept Realtime traffic for testing
 */
interface RealtimeProxy {
    /**
     * Start the proxy listening for connections
     */
    fun start()

    /**
     * Stop the proxy and close any active connetions
     */
    fun stop()

    /**
     * The host address the proxy will listen on
     */
    val listenHost: String

    /**
     * The port the proxy will be listening on
     */
    val listenPort: Int
}

/**
 * A TCP Proxy, which can run locally and intercept traffic to Ably realtime.
 *
 * This proxy is only capable of simulating faults at the transport layer, such
 * as connections being interrupted or packets being dropped entirely.
 */
class Layer4Proxy(
    private val id: String,
    override val listenHost: String = PROXY_HOST,
    override val listenPort: Int = PROXY_PORT,
    private val targetAddress: String = REALTIME_HOST,
    private val targetPort: Int = REALTIME_PORT,
) : RealtimeProxy {

    private val logger = LoggerFactory.getLogger("Layer4Proxy ($id)")

    private var server: ServerSocket? = null
    private val sslSocketFactory = SSLSocketFactory.getDefault()
    private val connections: MutableList<Layer4ProxyConnection> = mutableListOf()

    /**
     * Flag mutated by fault implementations to hang the TCP connection
     */
    var isForwarding = true

    /**
     * Block current thread and wait for a new incoming client connection on the server socket.
     * Returns a connection object when a client has connected.
     */
    private fun accept(): Layer4ProxyConnection {
        val clientSock = server?.accept()
        logger.debug("accepted connection")

        val serverSock = sslSocketFactory.createSocket(targetAddress, targetPort)
        val conn = Layer4ProxyConnection(id, serverSock, clientSock!!, targetAddress, parentProxy = this)
        connections.add(conn)
        return conn
    }

    /**
     * Close open connections and stop listening for new local connections
     */
    override fun stop() {
        server?.close()
        server = null

        connections.forEach {
            it.stop()
        }
        connections.clear()
    }

    /**
     * Begin a background thread listening for local Realtime connections
     */
    override fun start() {
        if (server != null) {
            logger.debug("start() called while already running")
            return
        }

        server = ServerSocket(listenPort)
        Thread {
            while (true) {
                logger.debug("proxy trying to accept")
                try {
                    val conn = this.accept()
                    logger.debug("proxy starting to run")
                    conn.run()
                } catch (e: Exception) {
                    logger.debug("proxy shutting down: " + e.message)
                    break
                }
            }
        }.start()
    }
}

/**
 * A TCP Proxy connection between a local client and the remote Ably service.
 */
internal class Layer4ProxyConnection(
    private val id: String,
    private val server: Socket,
    private val client: Socket,
    private val targetHost: String,
    private val parentProxy: Layer4Proxy
) {

    private val logger = LoggerFactory.getLogger("Layer4ProxyConnection ($id)")

    /**
     * Starts two threads, one forwarding traffic in each direction between
     * the local client and the Ably Realtime service.
     */
    fun run() {
        Thread { proxy(server, client, true) }.start()
        Thread { proxy(client, server) }.start()
    }

    /**
     * Close socket connections, causing proxy threads to exit.
     */
    fun stop() {
        try {
            server.close()
        } catch (e: Exception) {
            logger.debug("stop() server: $e", e)
        }

        try {
            client.close()
        } catch (e: Exception) {
            logger.debug("stop() client: $e", e)
        }
    }

    /**
     * Copies traffic between source and destination sockets, rewriting the
     * HTTP host header if requested to remove the proxy host details.
     */
    private fun proxy(dstSock: Socket, srcSock: Socket, rewriteHost: Boolean = false) {
        try {
            val dst = dstSock.getOutputStream()
            val src = srcSock.getInputStream()
            val buff = ByteArray(4096)
            var bytesRead: Int

            // deal with the initial HTTP upgrade packet
            bytesRead = src.read(buff)
            if (bytesRead < 0) {
                return
            }

            // HTTP is plaintext so we can just read it
            val msg = String(buff, 0, bytesRead)
            logger.debug(String(buff.copyOfRange(0, bytesRead)))
            if (rewriteHost) {
                val hostHeaderRegex = "Host: .*?\r\n".toRegex()
                val newMsg = msg.replace(
                    hostHeaderRegex,
                    replacement = "Host: $targetHost\r\n"
                )
                val newBuff = newMsg.toByteArray()
                dst.write(newBuff, 0, newBuff.size)
            } else {
                dst.write(buff, 0, bytesRead)
            }

            while (-1 != src.read(buff).also { bytesRead = it }) {
                if (parentProxy.isForwarding) {
                    dst.write(buff, 0, bytesRead)
                }
            }
        } catch (ignored: SocketException) {
        } catch (e: Exception) {
            logger.debug("$e", e)
        } finally {
            try {
                srcSock.close()
            } catch (ignored: Exception) {
            }
        }
    }
}

/**
 * A WebSocket proxy for realtime connections, to allow interventions at
 * the Ably protocol level.
 */
class Layer7Proxy(
    private val id: String,
    override val listenHost: String = PROXY_HOST,
    override val listenPort: Int = PROXY_PORT,
    private val targetHost: String = REALTIME_HOST,
    private val targetPort: Int = REALTIME_PORT
) : RealtimeProxy {

    val logger = LoggerFactory.getLogger("Layer7Proxy ($id)")

    private var server: ApplicationEngine? = null
    var interceptor: Layer7Interceptor = PassThroughInterceptor()

    override fun start() {
        logger.debug("starting...")
        server = embeddedServer(
            ServerCIO,
            port = listenPort,
            host = listenHost
        ) {
            install(CallLogging) {
                level = Level.TRACE
            }
            install(WebSockets)
            routing {
                wsProxy(
                    path = "/",
                    target = Url("wss://$targetHost:$targetPort/"),
                    parent = this@Layer7Proxy
                )
            }
        }.start(wait = false)
    }

    override fun stop() {
        logger.debug("stopping...")
        server?.stop()
    }

    /**
     * Receives frames from incoming channel and forwards to receiver as appropriate,
     * calling an intercept to see if any interventions are required.
     */
    suspend fun forwardFrames(
        direction: FrameDirection,
        incoming: ReceiveChannel<Frame>,
        clientSession: ClientWebSocketSession,
        serverSession: WebSocketServerSession,
    ) {
        for (received in incoming) {
            logger.debug("(raw) [$direction] ${logFrame(received)}")
            try {
                for (action in interceptor.interceptFrame(direction, received)) {
                    logger.debug("(forwarding) [${action.direction}]: ${logFrame(action.frame)}")
                    when (action.direction) {
                        FrameDirection.ClientToServer -> {
                            clientSession.send(action.frame)
                            if (action.sendAndClose) {
                                clientSession.close()
                            }
                        }
                        FrameDirection.ServerToClient -> {
                            serverSession.send(action.frame)
                            if (action.sendAndClose) {
                                serverSession.close()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.debug("forwardFrames error: $e", e)
                throw(e)
            }
        }
    }
}

/**
 * Proxy a WebSocket connection to the remote URL, setting up coroutines
 * to send a receive frames in both directions
 */
fun Route.wsProxy(path: String, target: Url, parent: Layer7Proxy) {
    webSocketRaw(path) {
        parent.logger.debug("Client connected to $path")

        val serverSession = this
        val client = configureWsClient(parent)

        val params = parent.interceptor.interceptConnection(
            ConnectionParams.fromRequestParameters(call.request.queryParameters)
        )

        client.wsRaw(
            method = call.request.httpMethod,
            host = target.host,
            port = target.port,
            path = call.request.path(),
            request = {
                url.protocol = target.protocol
                url.port = target.port

                // Forward connection parameters and rewrite the Host header, as
                // it will be the proxy host by default
                params.applyToBuilder(url.parameters)
                headers["Host"] = target.host
            }
        ) {
            val clientSession = this

            val serverJob = launch {
                parent.logger.debug("==> (started)")
                parent.forwardFrames(
                    FrameDirection.ClientToServer,
                    serverSession.incoming,
                    clientSession,
                    serverSession
                )
            }

            val clientJob = launch {
                parent.logger.debug("<== (started)")
                parent.forwardFrames(
                    FrameDirection.ServerToClient,
                    clientSession.incoming,
                    clientSession,
                    serverSession
                )
            }

            listOf(serverJob, clientJob).joinAll()
        }
    }
}

/**
 * Return a Ktor HTTP Client configured for WebSockets and with logging
 * we can see in the Layer 7 proxy logs
 */
fun configureWsClient(parent: Layer7Proxy) =
    HttpClient(ClientCIO).config {
        install(io.ktor.client.plugins.websocket.WebSockets) {
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    parent.logger.debug("ktor client: $message")
                }
            }
            level = LogLevel.ALL
        }
    }

/**
 * Return a string representation of a WS Frame for logging purposes
 */
fun logFrame(frame: Frame) =
    if (frame.frameType == FrameType.BINARY) {
        (frame.data.unpack()).toString()
    } else {
        frame.toString()
    }

interface Layer7Interceptor {

    /**
     * Handle a new incoming connection with provided parameters.
     * Return (potentially) altered connection parameters and apply any
     * fault-specific side-effects internally.
     */
    fun interceptConnection(params: ConnectionParams): ConnectionParams

    /**
     * Intercept a Frame being passed through the proxy, returning a list
     * of Actions to be performed in response. Note that doing nothing
     * (i.e. passing through), is an Action in itself
     */
    fun interceptFrame(direction: FrameDirection, frame: Frame): List<Action>
}

/**
 * Ably WebSocket connection parameters.
 * Enables faults to make alterations to incoming request parameters, before
 * the corresponding outgoing connection is made.
 */
data class ConnectionParams(
    val clientId: String?,
    val connectionSerial: String?,
    val resume: String?,
    val key: String?,
    val heartbeats: String?,
    val v: String?,
    val format: String?,
    val agent: String?
) {
    companion object {

        /**
         * Construct ConnectionParams from an incoming WebSocket connection request
         */
        fun fromRequestParameters(params: Parameters) =
            ConnectionParams(
                clientId = params["clientId"],
                connectionSerial = params["connectionSerial"],
                resume = params["resume"],
                key = params["key"],
                heartbeats = params["heartbeats"],
                v = params["v"],
                format = params["format"],
                agent = params["agent"]
            )
    }

    /**
     * Apply the (potentially altered) connection parameters in this instance
     * to an outgoing connection WebSocket connection request
     */
    fun applyToBuilder(paramsBuilder: ParametersBuilder) {
        if (clientId != null) {
            paramsBuilder["clientId"] = clientId
        }
        if (connectionSerial != null) {
            paramsBuilder["connectionSerial"] = connectionSerial
        }
        if (resume != null) {
            paramsBuilder["resume"] = resume
        }
        if (key != null) {
            paramsBuilder["key"] = key
        }
        if (heartbeats != null) {
            paramsBuilder["heartbeats"] = heartbeats
        }
        if (v != null) {
            paramsBuilder["v"] = v
        }
        if (format != null) {
            paramsBuilder["format"] = format
        }
        if (agent != null) {
            paramsBuilder["agent"] = agent
        }
    }
}

/**
 * Direction of a WebSocket Frame being intercepted by the proxy
 */
enum class FrameDirection {
    ClientToServer,
    ServerToClient,
}

/**
 * Action an interception wants to perform in response to an observed
 * message, or potentially a sequence of messages if it's retaining state.
 */
data class Action(
    /**
     * Direction to send the frame in
     */
    val direction: FrameDirection,

    /**
     * Websocket frame to be sent
     */
    val frame: Frame,

    /**
     * Flag to instruct proxy to close connection after performing
     * this action
     */
    val sendAndClose: Boolean = frame.frameType == FrameType.CLOSE
)

/**
 * An interceptor implementation that passes all data through normally
 */
class PassThroughInterceptor : Layer7Interceptor {

    override fun interceptConnection(params: ConnectionParams) = params

    override fun interceptFrame(
        direction: FrameDirection,
        frame: Frame
    ) = listOf(Action(direction, frame))
}
