plugins {
    id("io.micronaut.build.internal.servlet.http-server-tck-module")
}

// The WebSocket tests of the server TCK on the built-in JDK HTTP server with the HttpServer
// provider of io.github.robaho:httpserver, which keeps the connection open after a 101 Switching
// Protocols. test-suite-http-server-tck-jdk runs the rest of the TCK on the provider of the JDK,
// which cannot upgrade a connection.
dependencies {
    testImplementation(projects.micronautHttpServerJdk)
    testImplementation(projects.micronautServletWebsocket)
    testImplementation(libs.robaho.httpserver)
    testImplementation(libs.managed.jakarta.websocket.api)
    testImplementation(libs.managed.jakarta.websocket.client.api)
}
