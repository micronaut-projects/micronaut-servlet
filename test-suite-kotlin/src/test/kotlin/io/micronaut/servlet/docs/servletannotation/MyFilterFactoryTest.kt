package io.micronaut.servlet.docs.servletannotation

import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

/**
 * Exercises the filter factory shown in the Servlet annotations section of the guide, so the
 * documented Kotlin sources are real, compiled and covered.
 */
@Property(name = "spec.name", value = "MyFilterFactoryTest")
@Property(name = "my.filter.mapping", value = "/mapped-filter/*")
@MicronautTest
class MyFilterFactoryTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun theFilterRunsForItsMappings() {
        client.toBlocking().retrieve("/extra-filter/attribute") shouldBe "true"
        client.toBlocking().retrieve("/mapped-filter/attribute") shouldBe "true"
    }

    @Test
    fun theFilterDoesNotRunForOtherPaths() {
        client.toBlocking().retrieve("/unfiltered/attribute") shouldBe "null"
    }
}
