plugins {
    id("io.micronaut.build.internal.servlet.module")
}

dependencies {
    api(libs.managed.servlet.api)
    implementation(mn.micronaut.core.processor)
    testImplementation(mn.micronaut.inject.java.test)
}

micronautBuild {
    binaryCompatibility.enabled.set(false)
}
