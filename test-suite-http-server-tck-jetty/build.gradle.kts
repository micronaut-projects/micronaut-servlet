plugins {
    id("io.micronaut.build.internal.servlet.http-server-tck-module")
}

dependencies {
    testImplementation(projects.micronautHttpServerJetty)
    testImplementation(mnValidation.micronaut.validation)
}
