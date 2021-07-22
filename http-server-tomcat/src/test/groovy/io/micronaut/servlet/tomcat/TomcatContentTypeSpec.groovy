package io.micronaut.servlet.tomcat

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.async.annotation.SingleResult
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Produces
import io.micronaut.http.client.RxHttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import spock.lang.Issue
import spock.lang.Specification

@MicronautTest
@Property(name = 'spec.name', value = 'TomcatContentTypeSpec')
@Issue('https://github.com/micronaut-projects/micronaut-servlet/issues/206')
class TomcatContentTypeSpec extends Specification {

    @Inject
    @Client('/contentType')
    RxHttpClient client

    @Issue('https://github.com/micronaut-projects/micronaut-servlet/issues/157')
    void 'test that expected content-type is received'() {
        when:
        def response = client.exchange(HttpRequest.GET('/about')).blockingFirst()

        then:
        response.contentType.isPresent()
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
    }

    void 'test that method returning String without @Produces will have JSON response content-type'() {
        when:
        def response = client.exchange(
                HttpRequest.POST('/default/simple', 'foobar'), String
        ).blockingFirst()

        then:
        response.contentType.isPresent()
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.body() == 'Body: foobar'
    }

    void 'test that method returning HttpResponse without @Produces will have JSON response content-type'() {
        when:
        def response = client.exchange(
                HttpRequest.POST('/default/response', 'foobar'), String
        ).blockingFirst()

        then:
        response.contentType.isPresent()
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.body() == 'Body: foobar'
    }

    void 'test that method returning Mono without @Produces will have JSON response content-type'() {
        when:
        def response = client.exchange(
                HttpRequest.POST('/default/reactive', 'foobar'), String
        ).blockingFirst()

        then:
        response.contentType.isPresent()
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.body() == 'Body: foobar'
    }

    void 'test that method returning String with @Produces TEXT_PLAIN will have text response content-type'() {
        when:
        def response = client.exchange(
                HttpRequest.POST('/plainText/simple', 'foobar'), String
        ).blockingFirst()

        then:
        response.contentType.isPresent()
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.body() == 'Body: foobar'
    }

    void 'test that method returning HttpResponse with @Produces TEXT_PLAIN will have text response content-type'() {
        when:
        def response = client.exchange(
                HttpRequest.POST('/plainText/response', 'foobar'), String
        ).blockingFirst()

        then:
        response.contentType.isPresent()
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.body() == 'Body: foobar'
    }

    void 'test that method returning Mono with @Produces TEXT_PLAIN will have text response content-type'() {
        when:
        def response = client.exchange(
                HttpRequest.POST('/plainText/reactive', 'foobar'), String
        ).blockingFirst()

        then:
        response.contentType.isPresent()
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.body() == 'Body: foobar'
    }

    @Requires(property = 'spec.name', value = 'TomcatContentTypeSpec')
    @Controller("/contentType/about")
    static class AboutController {
        @Get
        @Produces(MediaType.APPLICATION_JSON)
        HttpResponse<String> index() {
            HttpResponse.ok().body("OK")
        }
    }

    @Requires(property = 'spec.name', value = 'TomcatContentTypeSpec')
    @Controller('/contentType/default')
    static class DefaultContentTypeController extends ContentTypeControllerBase {
        @Post('/simple')
        String simple(@Body String text) {
            result(text)
        }
        @Post('/response')
        HttpResponse<String> response(@Body String text) {
            HttpResponse<String>.ok(result(text))
        }
        @Post('/reactive')
        @SingleResult
        Publisher<String> reactive(@Body String text) {
            Mono<String>.just(result(text))
        }
    }

    @Requires(property = 'spec.name', value = 'TomcatContentTypeSpec')
    @Controller('/contentType/plainText')
    @Produces(MediaType.TEXT_PLAIN)
    static class PlainTextContentTypeController extends ContentTypeControllerBase {
        @Post('/simple')
        String simple(@Body String text) {
            result(text)
        }
        @Post('/response')
        HttpResponse<String> response(@Body String text) {
            HttpResponse<String>.ok(result(text))
        }
        @Post('/reactive')
        @SingleResult
        Publisher<String> reactive(@Body String text) {
            Mono<String>.just(result(text))
        }
    }

    private static class ContentTypeControllerBase {
        @SuppressWarnings('GrMethodMayBeStatic')
        String result(String text) {
            "Body: $text"
        }
    }

}
