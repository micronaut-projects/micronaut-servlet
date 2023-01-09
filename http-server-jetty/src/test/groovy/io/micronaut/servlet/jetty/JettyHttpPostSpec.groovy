
package io.micronaut.servlet.jetty

import groovy.transform.EqualsAndHashCode
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.Introspected
import io.micronaut.core.async.annotation.SingleResult
import io.micronaut.core.type.Argument
import io.micronaut.core.util.StringUtils
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.client.multipart.MultipartBody
import io.micronaut.http.multipart.CompletedFileUpload
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.reactivestreams.Publisher
import spock.lang.Specification

import java.nio.charset.StandardCharsets

/**
 * @author Graeme Rocher
 * @since 1.0
 */
@MicronautTest
@Property(name = 'spec.name', value = 'JettyHttpPostSpec')
@Property(name = "micronaut.server.multipart.enabled", value = StringUtils.TRUE)
class JettyHttpPostSpec extends Specification {
    @Inject
    @Client("/")
    HttpClient client

    @Inject
    PostClient postClient

    void "test send invalid http method"() {
        given:
        def book = new Book(title: "The Stand", pages: 1000)

        when:
        client.toBlocking().exchange(
                HttpRequest.PATCH("/post/simple", book)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-My-Header", "Foo"),
                Book
        )

        then:
        def e = thrown(HttpClientResponseException)
        e.response.getBody(Map).get()._embedded.errors[0].message == "Method [PATCH] not allowed for URI [/post/simple]. Allowed methods: [POST]"
    }

    void "test simple post request with JSON"() {
        given:
        def book = new Book(title: "The Stand", pages: 1000)

        when:
        HttpResponse<Book> response = client.toBlocking().exchange(
                HttpRequest.POST("/post/simple", book)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-My-Header", "Foo"),
                Book
        )
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
        def book = new Book(title: "The Stand", pages: 1000)

        when:
        HttpResponse<Book> response = client.toBlocking().exchange(
                HttpRequest.POST("/post/title/{title}", book)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-My-Header", "Foo"),
                Book
        )
        Optional<Book> body = response.getBody()

        then:
        response.status == HttpStatus.OK
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.contentLength == 34
        body.isPresent()
        body.get() instanceof Book
        body.get().title == 'The Stand'
    }

    void "test simple post request with URI template and JSON Map"() {
        given:
        def book = [title: "The Stand", pages: 1000]

        when:
        HttpResponse<Map> response = client.toBlocking().exchange(
                HttpRequest.POST("/post/title/{title}", book)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-My-Header", "Foo"),
                Map
        )
        Optional<Map> body = response.getBody()

        then:
        response.status == HttpStatus.OK
        response.contentType.get() == MediaType.APPLICATION_JSON_TYPE
        response.contentLength == 34
        body.isPresent()
        body.get() instanceof Map
        body.get() == book
    }

    void "test simple post request with Form data"() {
        given:
        def book = new Book(title: "The Stand", pages: 1000)
        when:
        HttpResponse<Book> response = client.toBlocking().exchange(
                HttpRequest.POST("/post/form", book)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-My-Header", "Foo"),
                Book
        )
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
        def toSend = new Book(title: "The Stand", pages: 1000)

        when:
        Book book = client.toBlocking().retrieve(
                HttpRequest.POST("/post/simple", toSend)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-My-Header", "Foo"),
                Book
        )

        then:
        book == toSend
    }

    void "test simple post request with a queryValue "() {
        given:
        def toSend = new Book(title: "The Stand", pages: 1000)

        when:
        Book book = client.toBlocking().retrieve(
                HttpRequest.POST("/post/query?title=The%20Stand", toSend)
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-My-Header", "Foo"),
                Book
        )

        then:
        book == toSend
    }

    void "test simple post request with a queryValue and no body"() {
        when:
        Book book = client.toBlocking().retrieve(
                HttpRequest.POST("/post/queryNoBody?title=The%20Stand", "")
                        .accept(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-My-Header", "Foo"),
                Book
        )

        then:
        book.pages == 0
        book.title == "The Stand"
    }

    void "test url encoded request with a list of params"() {
        when:
        String body = client.toBlocking().retrieve(
                HttpRequest.POST("/post/multipleParams", [param: ["a", "b"]])
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(MediaType.TEXT_PLAIN_TYPE),
                String
        )

        then:
        body == "a,b"
    }

    void "test url encoded request with a list of params bound to a POJO"() {
        when:
        String body = client.toBlocking().retrieve(
                HttpRequest.POST("/post/multipleParamsBody", [param: ["a", "b"]])
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(MediaType.TEXT_PLAIN_TYPE),
                String
        )

        then:
        body == "a,b"
    }

    void "test multipart request with a list of params"() {
        when:
        String body = client.toBlocking().retrieve(
                HttpRequest.POST("/post/multipleParams", MultipartBody.builder()
                        .addPart("param", "a")
                        .addPart("param", "b")
                        .build()
                )
                        .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
                        .accept(MediaType.TEXT_PLAIN_TYPE),
                String
        )

        then:
        body == "a,b"
    }

    void "test multipart request with custom charset for part"() {
        when:
        String body = client.toBlocking().retrieve(
                HttpRequest.POST("/post/multipartCharset", MultipartBody.builder()
                        .addPart("file", "test.csv", new MediaType("text/csv; charset=ISO-8859-1"), "micronaut,rocks".getBytes(StandardCharsets.ISO_8859_1))
                        .build()
                )
                        .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
                        .accept(MediaType.TEXT_PLAIN_TYPE),
                String
        )

        then:
        body == StandardCharsets.ISO_8859_1.toString()
    }

    void "test url encoded request with a string body"() {
        when:
        String body = client.toBlocking().retrieve(
                HttpRequest.POST("/post/multipleParams", "param=a&param=b")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(MediaType.TEXT_PLAIN_TYPE),
                String
        )

        then:
        body == "a,b"
    }

    void "test content length is 0 with a post and no body"() {
        expect:
        postClient.call() == "0"
    }

    void "test simple post request url encoded"() {
        given:
        def toSend = new Book(title: "The Stand", pages: 1000)

        when:
        Book book = client.toBlocking().retrieve(
                HttpRequest.POST("/post/query/url-encoded", toSend)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE)
                        .accept(MediaType.APPLICATION_JSON_TYPE),
                Book
        )

        then:
        book == toSend
    }

    void "test posting an array of simple types"() {
        List<Boolean> booleans = client.toBlocking().retrieve(
                HttpRequest.POST("/post/booleans", "[true, true, false]"),
                Argument.of(List.class, Boolean.class)
        )

        expect:
        booleans[0] == true
        booleans[1] == true
        booleans[2] == false
    }

    void "test request generic type no body"() {
        when:
        def response = client.toBlocking().exchange(
                HttpRequest.POST('/post/requestObject', ''), String
        )

        then:
        response.body() == "request-object"
    }

    void "test multiple params single body"() {
        String data = client.toBlocking().retrieve(
                HttpRequest.POST("/post/bodyParts", '{"id":5,"name":"Sally"}')
                        .contentType(MediaType.APPLICATION_JSON_TYPE)
                        .accept(MediaType.TEXT_PLAIN_TYPE),
                String
        )

        expect:
        data == "5 - Sally"
        postClient.bodyParts("Joe", 6) == "6 - Joe"
    }

    void "test multiple uris"() {
        def client = this.postClient

        when:
        String val = client.multiple()

        then:
        val == "multiple mappings"

        when:
        val = client.multipleMappings()

        then:
        val == "multiple mappings"
    }

    void 'test posting publisher with #desc'() {
        when:
        String data = client.toBlocking().retrieve(
                HttpRequest.POST("/post/publisher", body),
                String
        )

        then:
        data == expected

        where:
        body                                                                     | expected                                                                 | desc
        '[{"title":"some book","pages":1},{"title":"necronomicon","pages":666}]' | '[{"title":"some book","pages":1},{"title":"necronomicon","pages":666}]' | 'multiple entries'
        '{"title":"some book","pages":1}'                                        | '[{"title":"some book","pages":1}]'                                      | 'single entry'
    }

    void 'test @SingleResult publisher with #desc'() {
        when:
        String data = client.toBlocking().retrieve(
                HttpRequest.POST("/post/single-publisher", body),
                String
        )

        then:
        data == expected

        where:
        body                                                                     | expected                          | desc
        '[{"title":"some book","pages":1},{"title":"necronomicon","pages":666}]' | '{"title":"some book","pages":1}' | 'multiple entries'
        '{"title":"some book","pages":1}'                                        | '{"title":"some book","pages":1}' | 'single entry'
    }

    @Requires(property = 'spec.name', value = 'JettyHttpPostSpec')
    @Controller('/post')
    static class PostController {

        @Post('/simple')
        Book simple(@Body Book book, @Header String contentType, @Header long contentLength, @Header accept, @Header('X-My-Header') custom) {
            assert contentType == MediaType.APPLICATION_JSON
            assert contentLength == 34
            assert accept == MediaType.APPLICATION_JSON
            assert custom == 'Foo'
            return book
        }

        @Post('/query')
        Book simple(@Body Book book, @QueryValue String title) {
            assert title == book.title
            return book
        }

        @Post(uri = '/query/url-encoded', consumes = MediaType.APPLICATION_FORM_URLENCODED)
        Book simpleUrlEncoded(@Body Book book, String title) {
            assert title == book.title
            return book
        }

        @Post('/queryNoBody')
        Book simple(@QueryValue("title") String title) {
            return new Book(title: title, pages: 0)
        }

        @Post('/noBody')
        String noBody(@Header("Content-Length") String contentLength) {
            return contentLength
        }

        @Post('/publisher')
        Publisher<Book> publisher(@Body Publisher<Book> bookPublisher) {
            return bookPublisher
        }

        @Post('/single-publisher')
        @SingleResult
        Publisher<Book> singlePublisher(@Body Publisher<Book> bookPublisher) {
            return bookPublisher
        }

        @Post('/title/{title}')
        Book title(@Body Book book, String title, @Header String contentType, @Header long contentLength, @Header accept, @Header('X-My-Header') custom) {
            assert title == book.title
            assert contentType == MediaType.APPLICATION_JSON
            assert contentLength == 34
            assert accept == MediaType.APPLICATION_JSON
            assert custom == 'Foo'
            return book
        }

        @Post(uri = '/form', consumes = MediaType.APPLICATION_FORM_URLENCODED)
        Book form(@Body Book book, @Header String contentType, @Header long contentLength, @Header accept, @Header('X-My-Header') custom) {
            assert contentType == MediaType.APPLICATION_FORM_URLENCODED
            assert contentLength == 26
            assert accept == MediaType.APPLICATION_JSON
            assert custom == 'Foo'
            return book
        }

        @Post(uri = "/multipleParams",
                consumes = [MediaType.APPLICATION_FORM_URLENCODED, MediaType.MULTIPART_FORM_DATA],
                produces = MediaType.TEXT_PLAIN)
        String multipleParams(@Body Map data) {
            if (data.param instanceof Collection) {
                return ((Collection) data.param).join(",")
            } else {
                return "value=${data.param}"
            }
        }

        @Post(uri = "/multipleParamsBody",
                consumes = [MediaType.APPLICATION_FORM_URLENCODED, MediaType.MULTIPART_FORM_DATA],
                produces = MediaType.TEXT_PLAIN)
        String multipleParams(@Body Params data) {
            return data.param.join(",")
        }

        @Post(uri = "/multipartCharset",
                consumes = MediaType.MULTIPART_FORM_DATA,
                produces = MediaType.TEXT_PLAIN)
        String multipartCharset(@Body CompletedFileUpload file) {
            return file.getContentType().get().getCharset().get()
        }

        @Post(uri = "/booleans")
        List<Boolean> booleans(@Body List<Boolean> booleans) {
            return booleans
        }

        @Post("/requestObject")
        String requestObject(HttpRequest<Object> request) {
            "request-object"
        }

        @Post(uri = "/bodyParts", produces = MediaType.TEXT_PLAIN)
        String bodyParts(String name, Integer id) {
            "$id - $name"
        }

        @Post(uris = ["/multiple", "/multiple/mappings"])
        String multipleMappings() {
            return "multiple mappings"
        }
    }

    @Introspected
    @EqualsAndHashCode
    static class Book {
        String title
        Integer pages
    }

    @Introspected
    static class Params {
        List<String> param
    }

    @Requires(property = 'spec.name', value = 'JettyHttpPostSpec')
    @Client("/post")
    static interface PostClient {

        @Post("/noBody")
        String call()

        @Post(uri = "/bodyParts", consumes = MediaType.TEXT_PLAIN)
        String bodyParts(String name, Integer id)

        @Post("/multiple")
        String multiple()

        @Post("/multiple/mappings")
        String multipleMappings()
    }
}
