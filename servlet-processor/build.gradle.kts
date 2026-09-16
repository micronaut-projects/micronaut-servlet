plugins {
    id("io.micronaut.build.internal.servlet.module")
}

dependencies {
    api(libs.managed.servlet.api)
    implementation(mn.micronaut.core.processor)
    testImplementation(mn.micronaut.inject.java.test)
    // The Jakarta WebSocket mappers refer to both APIs by name only, so these are needed just to
    // compile the endpoints the tests declare and to resolve the Micronaut annotations they map to.
    testImplementation(libs.managed.jakarta.websocket.api)
    testImplementation(libs.managed.jakarta.websocket.client.api)
    testImplementation(mn.micronaut.websocket)
}
