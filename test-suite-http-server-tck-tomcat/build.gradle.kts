plugins {
    id("io.micronaut.build.internal.servlet.http-server-tck-module")
}

dependencies {
    testImplementation(projects.micronautHttpServerTomcat)
}

// the engine-independent fuzz suite is shared by all the TCK modules
sourceSets {
    named("test") {
        java.srcDir("../test-suite-http-server-fuzz/java")
    }
}

tasks.withType<Test>().configureEach {
    // forwards the fuzz switches: -Dfuzz.seed, -Dfuzz.only and -Dfuzz.runKnown
    System.getProperties().stringPropertyNames().filter { it.startsWith("fuzz.") }.forEach { systemProperty(it, System.getProperty(it)) }
}
