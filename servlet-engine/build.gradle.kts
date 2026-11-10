plugins {
    id("io.micronaut.build.internal.servlet.module")
}

dependencies {
    annotationProcessor(mn.micronaut.graal)
    annotationProcessor(projects.micronautServletProcessor)

    api(projects.micronautServletApi)
    api(libs.managed.servlet.api)

    implementation(mnReactor.micronaut.reactor)
    // the LiveReload script filter exists only when the development launcher runs the application
    compileOnly(mn.micronaut.dev)
    compileOnly(mn.micronaut.discovery.core)
    implementation(mn.micronaut.jackson.core)

    testAnnotationProcessor(mn.micronaut.inject.java)
    testImplementation(libs.junit.jupiter.engine)
}
