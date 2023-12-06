plugins {
    id("io.micronaut.build.internal.servlet.module")
}

dependencies {
    compileOnly(mn.micronaut.json.core)
    implementation(mn.micronaut.inject)
    implementation(mn.micronaut.http.server)
    implementation(mn.micronaut.http.netty)
    implementation(mn.micronaut.router)
    implementation(mnReactor.micronaut.reactor)
    testAnnotationProcessor(mn.micronaut.inject.java)
}
