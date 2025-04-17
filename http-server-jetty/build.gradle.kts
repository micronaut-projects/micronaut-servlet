plugins {
    id("io.micronaut.build.internal.servlet.implementation")
}

dependencies {
    implementation(libs.jetty.servlet)
    implementation(libs.jetty.http2.server)
    implementation(libs.jetty.alpn.server)
    implementation(libs.logback.access.jetty12)
    testImplementation(libs.bcpkix)
    testImplementation(libs.jetty.alpn.conscrypt.server)
    testCompileOnly(mnValidation.micronaut.validation.processor)
    testAnnotationProcessor(mnValidation.micronaut.validation.processor)
    testAnnotationProcessor(projects.micronautServletProcessor)
    testImplementation(mnValidation.micronaut.validation)
    testImplementation(mnSerde.micronaut.serde.jackson)
    testImplementation(mnLogging.logback.classic)
}
