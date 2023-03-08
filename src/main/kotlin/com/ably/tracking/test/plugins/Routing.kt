package com.ably.tracking.test.plugins

import com.ably.tracking.test.faults.AttachUnresponsive
import com.ably.tracking.test.faults.DetachUnresponsive
import com.ably.tracking.test.faults.DisconnectAndSuspend
import com.ably.tracking.test.faults.DisconnectWithFailedResume
import com.ably.tracking.test.faults.EnterFailedWithNonfatalNack
import com.ably.tracking.test.faults.EnterUnresponsive
import com.ably.tracking.test.faults.FaultSimulation
import com.ably.tracking.test.faults.FaultType
import com.ably.tracking.test.faults.NullApplicationLayerFault
import com.ably.tracking.test.faults.NullTransportFault
import com.ably.tracking.test.faults.ReenterOnResumeFailed
import com.ably.tracking.test.faults.TcpConnectionRefused
import com.ably.tracking.test.faults.TcpConnectionUnresponsive
import com.ably.tracking.test.faults.UpdateFailedWithNonfatalNack
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.util.UUID

val allFaults = listOf(
    NullTransportFault.fault,
    NullApplicationLayerFault.fault,
    TcpConnectionRefused.fault,
    TcpConnectionUnresponsive.fault,
    AttachUnresponsive.fault,
    DetachUnresponsive.fault,
    DisconnectWithFailedResume.fault,
    EnterFailedWithNonfatalNack.fault,
    UpdateFailedWithNonfatalNack.fault,
    DisconnectAndSuspend.fault,
    ReenterOnResumeFailed.fault,
    EnterUnresponsive.fault,
)

val faultSimulations = mutableMapOf<String, FaultSimulation>()

@Serializable
data class ProxyDto(val listenPort: Int)

@Serializable
data class FaultSimulationDto(val id: String, val name: String, val type: FaultType, val proxy: ProxyDto)

fun Application.configureRouting() {
    routing {
        get("/faults") {
            call.respond(allFaults.map { it.name })
        }

        post("/faults/{name}/simulation") {
            val name = context.parameters["name"]!!
            val fault = allFaults.first { it.name == name }

            val id = UUID.randomUUID().toString()
            val faultSimulation = fault.simulate(id)

            faultSimulation.proxy.start()

            synchronized(faultSimulations) {
                faultSimulations[id] = faultSimulation
            }

            val response = FaultSimulationDto(
                id = id,
                name = name,
                type = faultSimulation.type,
                proxy = ProxyDto(
                    listenPort = faultSimulation.proxy.listenPort
                )
            )

            call.respond(response)
        }

        post("/fault-simulations/{id}/enable") {
            val id = context.parameters["id"]!!
            val faultSimulation = synchronized(faultSimulations) {
                faultSimulations[id]!!
            }

            faultSimulation.enable()

            call.respond(200)
        }

        post("/fault-simulations/{id}/resolve") {
            val id = context.parameters["id"]!!
            val faultSimulation = synchronized(faultSimulations) {
                faultSimulations[id]!!
            }

            faultSimulation.resolve()

            call.respond(200)
        }

        post("/fault-simulations/{id}/clean-up") {
            val id = context.parameters["id"]!!
            val faultSimulation = synchronized(faultSimulations) {
                faultSimulations[id]!!
            }

            faultSimulation.cleanUp()

            call.respond(200)
        }
    }
}
