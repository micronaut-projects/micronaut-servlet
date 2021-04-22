
package io.micronaut.servlet.jetty

import groovy.transform.EqualsAndHashCode
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.reactivex.Flowable
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Header
import io.micronaut.http.client.exceptions.HttpClientException
import io.micronaut.http.annotation.Put
import spock.lang.Issue
import spock.lang.PendingFeature
import spock.lang.Specification

import io.micronaut.core.annotation.Nullable
import javax.inject.Inject

/**
 * @author Graeme Rocher
 * @since 1.0
 */
@MicronautTest
@Property(name = 'spec.name', value = 'JettyHttpPutSpec')
class JettyHttpPutSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Inject
    MyPutClient myPutClient

    void "test send invalid http method"() {
        given:
        def book = new Book(title: "The Stand", pages: 1000)

        when:
        Flowable<HttpResponse<Book>> flowable = Flowable.fromPublisher(client.exchange(
                HttpRequest.PATCH("/put/simple", book)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-My-Header", "Foo"),

                Book
        ))
        flowable.blockingFirst()

        then:
        def e = thrown(HttpClientException)
        e.message == "Method [PATCH] not allowed for URI [/put/simple]. Allowed methods: [PUT]"
    }
    void "test simple post request with JSON"() {
        given:
        def book = new Book(title: "The Stand", pages: 1000)

        when:
        Flowable<HttpResponse<Book>> flowable = Flowable.fromPublisher(client.exchange(
                HttpRequest.PUT("/put/simple", book)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-My-Header", "Foo"),

                Book
        ))
        HttpResponse<Book> response = flowable.blockingFirst()
        Optional<Book> body = response.getBody()

        then:
        response.status == HttpStatus.OK
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.contentLength == 34
        body.isPresent()
        body.get() instanceof Book
        body.get() == book
    }

    void "test simple post request with URI template and JSON"() {
        given:
        def book = new Book(title: "The Stand",pages: 1000)
        when:
        Flowable<HttpResponse<Book>> flowable = Flowable.fromPublisher(client.exchange(
                HttpRequest.PUT("/put/title/{title}", book)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-My-Header", "Foo"),

                Book
        ))
        HttpResponse<Book> response = flowable.blockingFirst()
        Optional<Book> body = response.getBody()

        then:
        response.status == HttpStatus.OK
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.contentLength == 34
        body.isPresent()
        body.get() instanceof Book
        body.get().title == 'The Stand'
    }


    void "test simple post request with Form data"() {
        given:
        def book = new Book(title: "The Stand", pages: 1000)
        when:
        Flowable<HttpResponse<Book>> flowable = Flowable.fromPublisher(client.exchange(
                HttpRequest.PUT("/put/form", book)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-My-Header", "Foo"),

                Book
        ))
        HttpResponse<Book> response = flowable.blockingFirst()
        Optional<Book> body = response.getBody()

        then:
        response.status == HttpStatus.OK
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.contentLength == 34
        body.isPresent()
        body.get() instanceof Book
        body.get().title == 'The Stand'
    }

    void "test simple post retrieve blocking request with JSON"() {
        given:
        def toSend = new Book(title: "The Stand",pages: 1000)
        when:
        BlockingHttpClient blockingHttpClient = client.toBlocking()
        Book book = blockingHttpClient.retrieve(
                HttpRequest.PUT("/put/simple", toSend)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-My-Header", "Foo"),

                Book
        )

        then:
        book == toSend
    }

    void "test binding multiple options to a json body"() {
        when:
        BlockingHttpClient blockingHttpClient = client.toBlocking()
        String result = blockingHttpClient.retrieve(
                HttpRequest.PUT("/put/optionalJson", [enable: true])
                        .accept(MediaType.TEXT_PLAIN),

                String
        )

        then:
        result == "enable=true"
    }

    void "test multiple uris"() {
        def client = this.myPutClient

        when:
        String val = client.multiple()

        then:
        val == "multiple mappings"

        when:
        val = client.multipleMappings()

        then:
        val == "multiple mappings"
    }

    @Issue("https://github.com/micronaut-projects/micronaut-core/issues/2757")
    void "test put with nullable header"() throws Exception {
        //This test verifies that the body is not read in an attempt to populate the header argument
        MutableHttpRequest<?> request = HttpRequest.PUT("/put/nullableHeader", "body".getBytes()).
                contentType(MediaType.TEXT_PLAIN_TYPE)

        String body = client.toBlocking().retrieve(request)

        expect:
        body == "put done"
    }

    @Requires(property = 'spec.name', value = 'JettyHttpPutSpec')
    @Controller('/put')
    static class PostController {

        @Put('/simple')
        Book simple(@Body Book book, @Header String contentType, @Header long contentLength, @Header accept, @Header('X-My-Header') custom) {
            assert contentType == MediaType.APPLICATION_JSON
            assert contentLength == 34
            assert accept == MediaType.APPLICATION_JSON
            assert custom == 'Foo'
            return book
        }

        @Put('/title/{title}')
        Book title(@Body Book book, String title, @Header String contentType, @Header long contentLength, @Header accept, @Header('X-My-Header') custom) {
            assert title == book.title
            assert contentType == MediaType.APPLICATION_JSON
            assert contentLength == 34
            assert accept == MediaType.APPLICATION_JSON
            assert custom == 'Foo'
            return book
        }

        @Put(value = '/form', consumes = MediaType.APPLICATION_FORM_URLENCODED)
        Book form(@Body Book book, @Header String contentType, @Header long contentLength, @Header accept, @Header('X-My-Header') custom) {
            assert contentType == MediaType.APPLICATION_FORM_URLENCODED
            assert contentLength == 26
            assert accept == MediaType.APPLICATION_JSON
            assert custom == 'Foo'
            return book
        }

        @Put(value = '/optionalJson', produces = MediaType.TEXT_PLAIN)
        String optionalJson(Optional<Boolean> enable, Optional<Integer> multiFactorCode) {
            StringBuilder sb = new StringBuilder()
            enable.ifPresent( { val ->
                sb.append("enable=").append(val)
            })
            multiFactorCode.ifPresent({ code ->
                sb.append("multiFactorCode=").append(code)
            })
            sb.toString()
        }

        @Put(uris = ["/multiple", "/multiple/mappings"])
        String multipleMappings() {
            return "multiple mappings"
        }

        @Put(value = "/nullableHeader", consumes = MediaType.ALL, produces = MediaType.TEXT_PLAIN)
        String putNullableHeader(@Body final Flowable<byte[]> contents,
                                 @Nullable @Header("foo") final String auth) {

            return "put done"
        }
    }

    @EqualsAndHashCode
    static class Book {
        String title
        Integer pages
    }

    @Requires(property = 'spec.name', value = 'JettyHttpPutSpec')
    @Client("/put")
    static interface MyPutClient {

        @Put("/multiple")
        String multiple()

        @Put("/multiple/mappings")
        String multipleMappings()

    }
}
