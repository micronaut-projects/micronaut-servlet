import io.micronaut.build.TestFramework
plugins {
    id("io.micronaut.build.internal.servlet.implementation")
}
dependencies {
    api(projects.micronautServletEngine)
    testAnnotationProcessor(mnSerde.micronaut.serde.processor)
    testImplementation(mnSerde.micronaut.serde.jackson)
    testImplementation(mnLogging.logback.classic)
}

micronautBuild {
    testFramework = TestFramework.JUNIT6
}
micronautBuild {
    binaryCompatibility.enabled = false
}
