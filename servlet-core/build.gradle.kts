plugins {
    id("io.micronaut.build.internal.servlet.module")
}

dependencies {
    api(mn.micronaut.inject)
    api(mn.micronaut.http.server)
    compileOnly(mn.micronaut.json.core)
    implementation(mn.micronaut.router)
    implementation(mnReactor.micronaut.reactor)
    testAnnotationProcessor(mn.micronaut.inject.java)
}
