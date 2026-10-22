plugins {
    id("io.micronaut.build.internal.servlet.http-server-tck-module")
}

dependencies {
    testImplementation(projects.micronautHttpServerJetty)
    // the WebSocket tests of the TCK, on the Jakarta WebSocket implementation of Jetty
    testImplementation(projects.micronautServletWebsocket)
    testImplementation(libs.jetty.websocket.jakarta.server) {
        // Only used by Jetty's WebAppContext configuration, which embedded mode never loads.
        exclude(group = "org.eclipse.jetty.ee10", module = "jetty-ee10-annotations")
    }
}

tasks.withType<Test>().configureEach {
    // forwards the switches of the TCK fuzz suite: -Dfuzz.seed, -Dfuzz.only, -Dfuzz.runKnown and -Dfuzz.knownFailures
    System.getProperties().stringPropertyNames().filter { it.startsWith("fuzz.") }.forEach { systemProperty(it, System.getProperty(it)) }
}
