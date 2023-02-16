# Ably Asset Tracking Test Proxy

## Description

Provides a REST API for creating and managing proxies which are able to simulate connectivity faults that might occur during use of the Ably Asset Tracking SDKs.

This is currently just intended as a tool for testing the Asset Tracking SDKs but may eventually become more generally useful for our Ably client libraries, hence the generic repository name.

## Usage

You will need to have a Java runtime installed. (The exact version does not matter; Gradle will take care of fetching and installing the correct version if needed.)

### Running

#### Running locally

To run the server in the foreground:

```bash
./gradlew run
```

#### Running in CI

Alternatively, you can run it as a background service (useful for SDKs’ CI jobs):

```bash
./start-service
```

This script uses `sudo` so it’s probably not something you want to use locally.

### Accessing

The above steps will start a web server at `http://localhost:8080`, exposing a REST API which is documented in the OpenAPI spec contained in [`openapi.yaml`](openapi.yaml).
