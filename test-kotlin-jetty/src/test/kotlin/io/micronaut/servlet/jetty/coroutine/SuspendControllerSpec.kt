package io.micronaut.servlet.jetty.coroutine

import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micronaut.context.ApplicationContext
import io.micronaut.http.HttpHeaders.*
import io.micronaut.http.HttpMethod
import io.micronaut.http.HttpRequest.GET
import io.micronaut.http.HttpRequest.OPTIONS
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.runtime.server.EmbeddedServer
import kotlinx.coroutines.reactive.awaitSingle

class SuspendControllerSpec : StringSpec() {

    val embeddedServer = autoClose(
        ApplicationContext.run(
            EmbeddedServer::class.java, mapOf(
                "micronaut.server.cors.enabled" to true,
                "micronaut.server.cors.configurations.dev.allowedOrigins" to listOf("foo.com"),
                "micronaut.server.cors.configurations.dev.allowedMethods" to listOf("GET"),
                "micronaut.server.cors.configurations.dev.allowedHeaders" to listOf(ACCEPT, CONTENT_TYPE)
            )
        )
    )

    val client = autoClose(
        embeddedServer.applicationContext.createBean(HttpClient::class.java, embeddedServer.url)
    )

    init {
        "test suspend applies CORS options" {
            val origin = "foo.com"
            val headers = "$CONTENT_TYPE,$ACCEPT"
            val method = HttpMethod.GET
            val optionsResponse = client.exchange(
                OPTIONS<Any>("/suspend/greet")
                    .header(ORIGIN, origin)
                    .header(ACCESS_CONTROL_REQUEST_METHOD, method)
                    .header(ACCESS_CONTROL_REQUEST_HEADERS, headers)
            ).awaitSingle()

            optionsResponse.status shouldBe HttpStatus.OK
            optionsResponse.header(ACCESS_CONTROL_ALLOW_ORIGIN) shouldBe origin
            optionsResponse.header(ACCESS_CONTROL_ALLOW_METHODS) shouldBe method.toString()
            optionsResponse.headers.getAll(ACCESS_CONTROL_ALLOW_HEADERS).joinToString(",") shouldBe headers

            val response = client.exchange(
                GET<String>("/suspend/greet?name=Fred")
                    .header(ORIGIN, origin)
            ).awaitSingle()

            response.status shouldBe HttpStatus.OK
            response.header(ACCESS_CONTROL_ALLOW_ORIGIN) shouldBe origin
        }

        "test suspend service with retries" {
            val response = client.exchange(GET<Any>("/suspend/callSuspendServiceWithRetries"), String::class.java).awaitSingle()
            val body = response.body.get()

            body shouldBe "delayedCalculation1"
            response.status shouldBe HttpStatus.OK
        }

        "test suspend service with retries blocked" {
            val response = client.exchange(GET<Any>("/suspend/callSuspendServiceWithRetriesBlocked"), String::class.java).awaitSingle()
            val body = response.body.get()

            body shouldBe "delayedCalculation2"
            response.status shouldBe HttpStatus.OK
        }

        "test suspend" {
            val response = client.exchange(GET<Any>("/suspend/simple"), String::class.java).awaitSingle()
            val body = response.body.get()

            body shouldBe "Hello"
            response.status shouldBe HttpStatus.OK
        }

        "test suspend delayed" {
            val response = client.exchange(GET<Any>("/suspend/delayed"), String::class.java).awaitSingle()
            val body = response.body.get()

            body shouldBe "Delayed"
            response.status shouldBe HttpStatus.OK
        }

        "test suspend status" {
            val response = client.exchange(GET<Any>("/suspend/status"), String::class.java).awaitSingle()

            response.status shouldBe HttpStatus.CREATED
        }

        "test suspend status delayed" {
            val response = client.exchange(GET<Any>("/suspend/statusDelayed"), String::class.java).awaitSingle()

            response.status shouldBe HttpStatus.CREATED
        }

        "test suspend invoked once" {
            val response = client.exchange(GET<Any>("/suspend/count"), Integer::class.java).awaitSingle()
            val body = response.body.get()

            body shouldBe 1
            response.status shouldBe HttpStatus.OK
        }

        "test error route" {
            val ex = shouldThrowExactly<HttpClientResponseException> {
                client.exchange(GET<Any>("/suspend/illegal"), String::class.java).awaitSingle()
            }
            val body = ex.response.getBody(String::class.java).get()

            ex.status shouldBe HttpStatus.BAD_REQUEST
            body shouldBe "illegal.argument"
        }

        "test suspend functions that throw exceptions inside withContext emit an error response to filters" {
            val ex = shouldThrowExactly<HttpClientResponseException> {
                client.exchange(GET<Any>("/suspend/illegalWithContext"), String::class.java).awaitSingle()
            }
            val body = ex.response.getBody(String::class.java).get()
            val filter = embeddedServer.applicationContext.getBean(SuspendFilter::class.java)

            ex.status shouldBe HttpStatus.BAD_REQUEST
            body shouldBe "illegal.argument"
            filter.responseStatus shouldBe HttpStatus.BAD_REQUEST
            filter.error should { t -> t is IllegalArgumentException }
        }
    }
}
