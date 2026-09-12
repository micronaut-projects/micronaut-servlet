package io.micronaut.servlet.http

import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpParameters
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpVersion
import io.micronaut.http.MediaType
import io.micronaut.http.body.MessageBodyHandlerRegistry
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable

/**
 * Whether a request carries a body has to be decided from framing, and the framing rules differ by protocol.
 */
class DefaultBodyBuilderSpec extends Specification {

    DefaultBodyBuilder builder = new DefaultBodyBuilder(MessageBodyHandlerRegistry.EMPTY)

    void "a declared length of zero means there is no body"() {
        given:
        def supplier = countingSupplier("ignored")

        when:
        def body = builder.buildBody(supplier, request(0, HttpVersion.HTTP_1_1, [:]))

        then:
        body == null
        supplier.calls == 0
    }

    void "an HTTP 1 request with no framing headers has no body, without touching the stream"() {
        given:
        def supplier = countingSupplier("ignored")

        when:
        def body = builder.buildBody(supplier, request(-1, HttpVersion.HTTP_1_1, [:]))

        then: "reading to find out would block on a container waiting for data that never comes"
        body == null
        supplier.calls == 0
    }

    void "an HTTP 1 request declaring chunked transfer has a body"() {
        given:
        def supplier = countingSupplier("chunked payload")

        when:
        def body = builder.buildBody(supplier, request(-1, HttpVersion.HTTP_1_1, [(HttpHeaders.TRANSFER_ENCODING): "chunked"]))

        then:
        new String(body as byte[], StandardCharsets.UTF_8) == "chunked payload"
        supplier.calls == 1
    }

    void "a declared length means there is a body"() {
        given:
        def supplier = countingSupplier("declared payload")

        when:
        def body = builder.buildBody(supplier, request(16, HttpVersion.HTTP_1_1, [:]))

        then:
        new String(body as byte[], StandardCharsets.UTF_8) == "declared payload"
    }

    void "an HTTP 2 request with no framing headers still delivers its body"() {
        given: "HTTP/2 frames the payload in DATA frames and forbids Transfer-Encoding, so neither header appears"
        def supplier = countingSupplier("http2 payload")

        when:
        def body = builder.buildBody(supplier, request(-1, HttpVersion.HTTP_2_0, [:]))

        then:
        new String(body as byte[], StandardCharsets.UTF_8) == "http2 payload"
    }

    void "an HTTP 2 request with an empty stream has no body"() {
        given:
        def supplier = countingSupplier("")

        when:
        def body = builder.buildBody(supplier, request(-1, HttpVersion.HTTP_2_0, [:]))

        then: "framing cannot say, so the stream decides"
        body == null
        supplier.calls == 1
    }

    void "a form submission is read from the parameters rather than the body"() {
        given:
        def supplier = countingSupplier("ignored")
        def request = request(-1, HttpVersion.HTTP_1_1, [:], MediaType.APPLICATION_FORM_URLENCODED_TYPE)

        when:
        def body = builder.buildBody(supplier, request)

        then:
        body == [name: ["value"]]
        supplier.calls == 0
    }

    private CountingSupplier countingSupplier(String content) {
        new CountingSupplier(content)
    }

    private static class CountingSupplier implements Callable<InputStream> {
        final String content
        int calls = 0

        CountingSupplier(String content) {
            this.content = content
        }

        @Override
        InputStream call() {
            calls++
            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))
        }
    }

    private HttpRequest<?> request(long contentLength,
                                   HttpVersion version,
                                   Map<String, String> headerValues,
                                   MediaType contentType = MediaType.APPLICATION_JSON_TYPE) {
        HttpHeaders headers = Stub(HttpHeaders) {
            contains(_ as String) >> { String name -> headerValues.containsKey(name) }
            get(_ as String) >> { String name -> headerValues.get(name) }
        }
        HttpParameters parameters = Stub(HttpParameters) {
            asMap() >> [name: ["value"]]
        }
        Stub(HttpRequest) {
            getContentLength() >> contentLength
            getHttpVersion() >> version
            getHeaders() >> headers
            getContentType() >> Optional.of(contentType)
            getParameters() >> parameters
            getAttributes() >> Stub(io.micronaut.core.convert.value.MutableConvertibleValues)
        }
    }
}
