package io.micronaut.servlet.tomcat

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.async.annotation.SingleResult
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest as MnHttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.MutableHttpHeaders
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Consumes
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Filter
import io.micronaut.http.annotation.Post
import io.micronaut.http.filter.HttpServerFilter
import io.micronaut.http.filter.ServerFilterChain
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import spock.lang.Specification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The limits of the forms the engine decodes itself: the buffer limit, the number of fields, malformed escapes,
 * and forms whose body a filter read first.
 */
@MicronautTest
@Property(name = 'spec.name', value = 'TomcatFormLimitsSpec')
@Property(name = 'micronaut.server.max-request-buffer-size', value = '64')
@Property(name = 'micronaut.http.forms.max-decoded-key-value-parameters', value = '4')
class TomcatFormLimitsSpec extends Specification {

    @Inject
    EmbeddedServer embeddedServer

    HttpClient client = HttpClient.newHttpClient()

    void "a form larger than the buffer limit is refused with 413"() {
        when:
        HttpResponse<String> response = post('/forms/name', 'name=' + ('x' * 200), chunked)

        then:
        response.statusCode() == 413

        where:
        chunked << [true, false]
    }

    void "a form with more fields than the limit is refused with 413"() {
        when:
        HttpResponse<String> response = post('/forms/name', 'name=a&b=1&c=2&d=3&e=4', chunked)

        then:
        response.statusCode() == 413

        where:
        chunked << [true, false]
    }

    void "a form within the limits is bound"() {
        when:
        HttpResponse<String> response = post('/forms/name', 'name=a&b=1&c=2&d=3', chunked)

        then:
        response.statusCode() == 200
        response.body() == 'a'

        where:
        chunked << [true, false]
    }

    void "a malformed escape in a form is a bad request"() {
        when:
        HttpResponse<String> response = post('/forms/name', 'name=%GG', chunked)

        then:
        response.statusCode() == 400

        where:
        chunked << [true, false]
    }

    void "the parameters of a form whose body a filter read a copy of are decoded from that copy"() {
        when:
        HttpResponse<String> response = post('/forms/tags', 'tag=one&tag=two', chunked, ['X-Read-Copy': 'true'])

        then:
        response.statusCode() == 200
        response.body() == '[one, two]'

        where:
        chunked << [true, false]
    }

    void "a Content-Type a filter changed is the one of the request"() {
        when:
        HttpResponse<String> response = post('/forms/content-type', 'name=a', false, ['X-Change-Content-Type': 'true'])

        then:
        response.statusCode() == 200
        response.body() == 'text/plain name=a'
    }

    private HttpResponse<String> post(String path, String body, boolean chunked, Map<String, String> headers = [:]) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(embeddedServer.URI.resolve(path))
            .header('Content-Type', MediaType.APPLICATION_FORM_URLENCODED)
            .POST(chunked
                ? HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString(body))
                : HttpRequest.BodyPublishers.ofString(body))
        headers.each { k, v -> builder.header(k, v) }
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Requires(property = 'spec.name', value = 'TomcatFormLimitsSpec')
    @Filter('/forms/**')
    static class FormFilter implements HttpServerFilter {
        @Override
        Publisher<MutableHttpResponse<?>> doFilter(MnHttpRequest<?> request, ServerFilterChain chain) {
            if (request.headers.contains('X-Change-Content-Type')) {
                // the headers of the request are mutable: a filter may change them in place
                ((MutableHttpHeaders) request.headers).set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN)
                return chain.proceed(request)
            }
            if (request.headers.contains('X-Read-Copy')) {
                def copy = ((io.micronaut.http.ServerHttpRequest<?>) request).byteBody().split(io.micronaut.http.body.ByteBody.SplitBackpressureMode.FASTEST)
                return Mono.fromCompletionStage(copy.buffer()).flatMapMany { buffered ->
                    buffered.close()
                    Flux.from(chain.proceed(request))
                }
            }
            return chain.proceed(request)
        }
    }

    @Requires(property = 'spec.name', value = 'TomcatFormLimitsSpec')
    @Controller('/forms')
    static class FormController {

        @Post('/name')
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        String name(String name) {
            name
        }

        @Post('/tags')
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        String tags(MnHttpRequest<?> request) {
            String.valueOf(request.parameters.getAll('tag'))
        }

        @Post('/content-type')
        @Consumes([MediaType.APPLICATION_FORM_URLENCODED, MediaType.TEXT_PLAIN])
        String contentType(MnHttpRequest<?> request, @Body String body) {
            request.contentType.get().toString() + ' ' + body
        }
    }
}
