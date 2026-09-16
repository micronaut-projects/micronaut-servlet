package io.micronaut.servlet.docs.tomcat

import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.apache.catalina.startup.Tomcat
import org.junit.jupiter.api.Test

/**
 * Exercises the listener shown in the Tomcat section of the guide, so the documented Kotlin
 * sources are real, compiled and covered. The test suite runs Jetty by default, so Tomcat is
 * enabled for this test only.
 */
@Property(name = "spec.name", value = "TomcatServerCustomizerTest")
@Property(name = "micronaut.server.jetty.enabled", value = "false")
@Property(name = "micronaut.server.tomcat.enabled", value = "true")
@MicronautTest
class TomcatServerCustomizerTest {

    @Inject
    lateinit var tomcat: Tomcat

    @Test
    fun theListenerCustomizesTheTomcatServer() {
        tomcat.server.utilityThreads shouldBe 2
    }
}
