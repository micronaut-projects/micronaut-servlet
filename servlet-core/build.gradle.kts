plugins {
    id("io.micronaut.build.internal.servlet.module")
}

dependencies {
    api(mn.micronaut.http.server)
    compileOnly(mn.micronaut.discovery.core)
    compileOnly(mn.micronaut.json.core)
    // only to recognise streamed responses produced by the Netty HTTP client (ProxyHttpClient); optional at runtime
    compileOnly(mn.micronaut.http.netty)
    implementation(mn.micronaut.router)
    implementation(mnReactor.micronaut.reactor)
    testAnnotationProcessor(mn.micronaut.inject.java)
}
