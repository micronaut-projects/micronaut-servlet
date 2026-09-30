package io.micronaut.servlet.docs.jetty

import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.eclipse.jetty.server.Server
import org.junit.jupiter.api.Test

/**
 * Exercises the listener shown in the Jetty section of the guide, so the documented Kotlin
 * sources are real, compiled and covered.
 */
@Property(name = "spec.name", value = "JettyServerCustomizerTest")
@MicronautTest
class JettyServerCustomizerTest {

    @Inject
    lateinit var server: Server

    @Test
    fun theListenerCustomizesTheJettyServer() {
        server.stopTimeout shouldBe 5000
    }
}
