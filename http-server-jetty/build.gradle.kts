plugins {
    id("io.micronaut.build.internal.servlet.implementation")
}

dependencies {
    implementation(libs.jetty.servlet)
    implementation(libs.jetty.http2.server)
    implementation(libs.jetty.alpn.server)
    implementation(libs.jetty.alpn.java.server)
    implementation(mnLogging.logback.core)//force version to avoid CVE-2025-11226
    compileOnly(libs.logback.access.jetty12)
    testImplementation(libs.bcpkix)
    testImplementation(libs.jetty.alpn.conscrypt.server)
    testCompileOnly(mnValidation.micronaut.validation.processor)
    testAnnotationProcessor(mnValidation.micronaut.validation.processor)
    testAnnotationProcessor(projects.micronautServletProcessor)
    testImplementation(mnValidation.micronaut.validation)
    testImplementation(mnSerde.micronaut.serde.jackson)
    testAnnotationProcessor(mnSerde.micronaut.serde.processor)
    testImplementation(mnLogging.logback.classic)
    testImplementation(projects.micronautServletWebsocket)
    testImplementation(libs.junit.jupiter.engine)
    // Exercises the reflective route for Jakarta decoders, encoders and configurators.
    testImplementation(mn.micronaut.reflection)
    testImplementation(libs.jetty.websocket.jakarta.server) {
        // Only used by Jetty's WebAppContext configuration, which embedded mode never loads.
        exclude(group = "org.eclipse.jetty.ee10", module = "jetty-ee10-annotations")
    }
}

// The WebSocket routes of the route builder are tested alike on each servlet runtime.
sourceSets.test {
    java.srcDir(rootProject.file("servlet-websocket/src/routeTest/java"))
}
