plugins {
    id("io.micronaut.build.internal.http-test-module")
}
repositories {
    mavenCentral()
}
val micronautVersion: String by project
dependencies {
    testImplementation(mn.micronaut.http.client)
    testImplementation(projects.httpServerTomcat)
    testImplementation(mn.micronaut.http.server.tck)
    testImplementation(libs.junit.platform.engine)
}
