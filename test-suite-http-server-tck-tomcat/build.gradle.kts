plugins {
    id("io.micronaut.build.internal.servlet.http-server-tck-module")
}

dependencies {
    // the PostDiscoveryFilter that excludes single TCK tests
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation(projects.micronautHttpServerTomcat)
    // the WebSocket tests of the TCK, on the Jakarta WebSocket implementation of the container
    testImplementation(projects.micronautServletWebsocket)
    testImplementation(libs.tomcat.embed.websocket)
}

tasks.withType<Test>().configureEach {
    // forwards the switches of the TCK fuzz suite: -Dfuzz.seed, -Dfuzz.only, -Dfuzz.runKnown and -Dfuzz.knownFailures
    System.getProperties().stringPropertyNames().filter { it.startsWith("fuzz.") }.forEach { systemProperty(it, System.getProperty(it)) }
}
