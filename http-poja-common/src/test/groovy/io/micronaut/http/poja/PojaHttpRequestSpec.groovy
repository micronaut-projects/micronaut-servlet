package io.micronaut.http.poja

import io.micronaut.core.convert.ArgumentConversionContext
import io.micronaut.core.convert.ConversionService
import io.micronaut.core.convert.value.ConvertibleValues
import io.micronaut.core.io.buffer.ByteArrayBufferFactory
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpMethod
import io.micronaut.http.MediaType
import io.micronaut.http.MutableHttpHeaders
import io.micronaut.http.MutableHttpParameters
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.body.ByteBody
import io.micronaut.http.body.ByteBodyFactory
import io.micronaut.http.cookie.Cookie
import io.micronaut.http.cookie.Cookies
import io.micronaut.http.poja.util.MultiValueHeaders
import io.micronaut.http.poja.util.MultiValuesQueryParameters
import io.micronaut.http.simple.cookies.SimpleCookies
import io.micronaut.servlet.http.ServletHttpResponse
import spock.lang.Specification

class PojaHttpRequestSpec extends Specification {

    void "form submission bodies are exposed as convertible values"() {
        given:
        def request = new TestPojaHttpRequest("first=one&empty=&second=two", MediaType.APPLICATION_FORM_URLENCODED_TYPE)

        when:
        Optional<ConvertibleValues> body = request.getBody(ConvertibleValues)

        then:
        body.present
        body.get().get("first", String).get() == "one"
        body.get().get("second", String).get() == "two"
        body.get().get("empty", String).empty
        request.formSubmission
    }

    void "multi value headers standardize names and mutate values"() {
        given:
        def headers = new MultiValueHeaders(["content-TYPE": ["text/plain"]], ConversionService.SHARED)

        expect:
        headers.get(HttpHeaders.CONTENT_TYPE) == "text/plain"
        headers.names().contains(HttpHeaders.CONTENT_TYPE)

        when:
        headers.add("x-test-header", "one")
        headers.add("X-Test-Header", "two")

        then:
        headers.getAll("X-Test-Header") == ["one", "two"]

        when:
        headers.remove("x-test-header")

        then:
        headers.getAll("X-Test-Header").empty

        when:
        headers.get(null)

        then:
        thrown(NullPointerException)
    }

    void "multi value query parameters add character sequence values"() {
        given:
        Map<CharSequence, List<String>> values = new LinkedHashMap<>()
        values.put("existing", ["1"])
        def parameters = new MultiValuesQueryParameters(values, ConversionService.SHARED)

        when:
        parameters.add("added", ["2", "3"])

        then:
        parameters.get("existing") == "1"
        parameters.getAll("added") == ["2", "3"]
        parameters.names().containsAll(["existing", "added"])
    }

    private static final class TestPojaHttpRequest extends PojaHttpRequest<Object, Object, Object> {
        private final ByteBody byteBody
        private final MutableHttpHeaders headers
        private final MutableHttpParameters parameters =
            new MultiValuesQueryParameters(Collections.emptyMap(), ConversionService.SHARED)
        private final Cookies cookies = new SimpleCookies(ConversionService.SHARED)

        TestPojaHttpRequest(String body, MediaType contentType) {
            super(ConversionService.SHARED, null)
            this.byteBody = ByteBodyFactory.createDefault(ByteArrayBufferFactory.INSTANCE).adapt(body.bytes)
            this.headers = new MultiValueHeaders([(HttpHeaders.CONTENT_TYPE): [contentType.toString()]], ConversionService.SHARED)
        }

        @Override
        ByteBody byteBody() {
            byteBody
        }

        @Override
        ServletHttpResponse<Object, ?> getResponse() {
            throw new UnsupportedOperationException()
        }

        @Override
        ServletHttpResponse<Object, ?> createResponse() {
            throw new UnsupportedOperationException()
        }

        @Override
        Object getNativeRequest() {
            new Object()
        }

        @Override
        MutableHttpParameters getParameters() {
            parameters
        }

        @Override
        HttpMethod getMethod() {
            HttpMethod.POST
        }

        @Override
        URI getUri() {
            URI.create("/")
        }

        @Override
        Cookies getCookies() {
            cookies
        }

        @Override
        MutableHttpRequest<Object> cookie(Cookie cookie) {
            cookies.put(cookie.name, cookie)
            this
        }

        @Override
        MutableHttpRequest<Object> uri(URI uri) {
            this
        }

        @Override
        <T> MutableHttpRequest<T> body(T body) {
            throw new UnsupportedOperationException()
        }

        @Override
        MutableHttpHeaders getHeaders() {
            headers
        }

        @Override
        Optional<Object> getBody() {
            getBody(Object)
        }

        @Override
        void setConversionService(ConversionService conversionService) {
        }
    }
}
