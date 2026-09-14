package io.micronaut.servlet.undertow

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

/**
 * A response whose length is already known must say so, on the blocking path as much as the asynchronous one.
 *
 * <p>Content-Length used to be refused along with Transfer-Encoding when set through the header map, which is how
 * the blocking transfer path sets it, so those responses went out chunked.</p>
 */
@MicronautTest
@Property(name = 'spec.name', value = 'UndertowBlockingContentLengthSpec')
@Property(name = 'micronaut.servlet.async-supported', value = 'false')
class UndertowBlockingContentLengthSpec extends Specification {

    private static final String LARGE_BODY = 'x' * (128 * 1024)

    @Inject
    @Client('/')
    HttpClient client

    void "a small response of known length carries Content-Length"() {
        when:
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET('/blocking-length/text'), String)

        then:
        response.status == HttpStatus.OK
        response.body() == 'a body of known length'
        response.header(HttpHeaders.CONTENT_LENGTH) == '22'
        response.header(HttpHeaders.TRANSFER_ENCODING) == null
    }

    void "a response larger than the container buffer still carries Content-Length"() {
        when: "the container cannot work the length out for itself, because it must start writing before the end"
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET('/blocking-length/large'), String)

        then:
        response.status == HttpStatus.OK
        response.body().length() == LARGE_BODY.length()
        response.header(HttpHeaders.CONTENT_LENGTH) == String.valueOf(LARGE_BODY.length())
        response.header(HttpHeaders.TRANSFER_ENCODING) == null
    }

    @Requires(property = 'spec.name', value = 'UndertowBlockingContentLengthSpec')
    @Controller('/blocking-length')
    static class BlockingLengthController {

        @Get(value = '/text', produces = MediaType.TEXT_PLAIN)
        String text() {
            'a body of known length'
        }

        @Get(value = '/large', produces = MediaType.TEXT_PLAIN)
        String large() {
            LARGE_BODY
        }
    }
}
