plugins {
    id("io.micronaut.build.internal.servlet.implementation")
}

dependencies {
    testAnnotationProcessor(mnValidation.micronaut.validation.processor)
    testAnnotationProcessor(projects.micronautServletProcessor)
    testImplementation(projects.micronautHttpServerJetty)
    testImplementation(libs.jetty.servlet)
    testImplementation(libs.jetty.alpn.conscrypt.server)
    testImplementation(mnValidation.micronaut.validation)
    testImplementation(mnSerde.micronaut.serde.jackson)
    testImplementation(mnLogging.logback.classic)
    testImplementation(libs.logback.access.jetty12)
    testCompileOnly(mnValidation.micronaut.validation.processor)
}

micronautBuild {
    binaryCompatibility {
        enabled = false
    }
}
