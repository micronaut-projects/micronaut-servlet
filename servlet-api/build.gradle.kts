plugins {
    id("io.micronaut.build.internal.servlet.module")
}

dependencies {
    api(projects.micronautServletCore)
    api(libs.managed.servlet.api)
}

micronautBuild {
    binaryCompatibility.enabled.set(false)
}
