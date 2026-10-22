import io.micronaut.build.TestFramework
plugins {
    id("io.micronaut.build.internal.servlet.implementation")
}
dependencies {
    api(projects.micronautServletEngine)
    // an application that uses WebSocket adds the Jakarta WebSocket API along with
    // micronaut-servlet-websocket: the ServerContainer of this runtime is only a bean then
    compileOnly(libs.managed.jakarta.websocket.api)
    compileOnly(libs.managed.jakarta.websocket.client.api)
    testAnnotationProcessor(mnSerde.micronaut.serde.processor)
    testImplementation(mnSerde.micronaut.serde.jackson)
    testImplementation(mnLogging.logback.classic)
    // a WebSocket handshake on the HttpServer provider of the JDK is refused
    testImplementation(projects.micronautServletWebsocket)
    testImplementation(libs.managed.jakarta.websocket.api)
    testImplementation(libs.managed.jakarta.websocket.client.api)
}

micronautBuild {
    testFramework = TestFramework.JUNIT6
}
micronautBuild {
    binaryCompatibility.enabled = false
}
