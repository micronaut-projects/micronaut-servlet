package io.micronaut.servlet.docs.servletapi

import io.kotest.matchers.shouldBe
import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.multipart.MultipartBody
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Test

/**
 * Exercises the controller shown in the Servlet API section of the guide, so the documented
 * Kotlin sources are real, compiled and covered.
 */
@Property(name = "spec.name", value = "DocsControllerTest")
@MicronautTest
class DocsControllerTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Test
    fun theServletRequestAndResponseCanBeInjected() {
        val response = client.toBlocking().exchange(HttpRequest.GET<Any>("/docs/hello?name=Fred"), String::class.java)

        response.status() shouldBe HttpStatus.ACCEPTED
        response.body() shouldBe "Hello Fred"
    }

    @Test
    fun readableAndWritableSimplifyIo() {
        val body = client.toBlocking().retrieve(HttpRequest.POST("/docs/writable", "Fred").contentType(MediaType.TEXT_PLAIN), String::class.java)

        body shouldBe "Hello Fred"
    }

    @Test
    fun partsCanBeInjectedWithPart() {
        val body = MultipartBody.builder()
            .addPart("attribute", "value")
            .addPart("one", "one.json", MediaType.APPLICATION_JSON_TYPE, """{"name":"Fred","age":20}""".toByteArray())
            .addPart("two", "two.txt", MediaType.TEXT_PLAIN_TYPE, "text".toByteArray())
            .addPart("three", "three.bin", MediaType.APPLICATION_OCTET_STREAM_TYPE, byteArrayOf(1, 2, 3))
            .addPart("four", "four.txt", MediaType.TEXT_PLAIN_TYPE, "raw".toByteArray())
            .addPart("five", "five.txt", MediaType.TEXT_PLAIN_TYPE, "part".toByteArray())
            .build()

        val result = client.toBlocking().retrieve(
            HttpRequest.POST("/docs/multipart", body).contentType(MediaType.MULTIPART_FORM_DATA_TYPE), String::class.java
        )

        result shouldBe "Ok"
    }
}
