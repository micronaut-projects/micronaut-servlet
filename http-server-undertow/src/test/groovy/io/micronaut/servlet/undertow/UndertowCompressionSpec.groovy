package io.micronaut.servlet.undertow

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpHeaders
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import reactor.core.publisher.Flux
import spock.lang.Specification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream

/**
 * Responses are compressed when the client accepts it and the body is worth compressing.
 *
 * <p>Uses the JDK client rather than the Micronaut one, because the Micronaut client decompresses transparently and
 * would hide whether anything was compressed at all.</p>
 */
@MicronautTest
@Property(name = 'spec.name', value = 'UndertowCompressionSpec')
class UndertowCompressionSpec extends Specification {

    static final String LARGE = 'a compressible body. ' * 500
    static final String SMALL = 'small'

    @Inject
    EmbeddedServer embeddedServer

    void "a large text response is compressed when the client accepts gzip"() {
        when:
        HttpResponse<byte[]> response = send('/compression/large', 'gzip')

        then:
        response.statusCode() == 200
        isGzip(response)
        response.body().length < LARGE.length()
        gunzip(response.body()) == LARGE
    }

    void "a small response is not worth compressing"() {
        when:
        HttpResponse<byte[]> response = send('/compression/small', 'gzip')

        then:
        response.statusCode() == 200
        !isGzip(response)
        new String(response.body()) == SMALL
    }

    void "a response is not compressed when the client does not accept it"() {
        when:
        HttpResponse<byte[]> response = send('/compression/large', 'identity')

        then: "containers differ over whether they name the identity encoding, so what matters is that it is not gzip"
        response.statusCode() == 200
        !isGzip(response)
        new String(response.body()) == LARGE
    }

    void "compression can be turned off"() {
        given:
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer, [
            'spec.name'                            : 'UndertowCompressionSpec',
            'micronaut.servlet.compression.enabled': false,
        ])

        when:
        HttpResponse<byte[]> response = send(server, '/compression/large', 'gzip')

        then:
        response.statusCode() == 200
        !isGzip(response)
        new String(response.body()) == LARGE

        cleanup:
        server.close()
    }

    void "the threshold and the content types are configurable"() {
        given: "only JSON is compressed, and only from 2KB"
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer, [
            'spec.name'                                  : 'UndertowCompressionSpec',
            'micronaut.servlet.compression.threshold'    : '2kb',
            'micronaut.servlet.compression.content-types': ['application/json'],
        ])

        when: "a large text response"
        HttpResponse<byte[]> text = send(server, '/compression/large', 'gzip')

        then: "its type is no longer compressed"
        !isGzip(text)
        new String(text.body()) == LARGE

        when: "a JSON response above the default threshold but below the configured one"
        HttpResponse<byte[]> smallJson = send(server, '/compression/json-small', 'gzip')

        then:
        !isGzip(smallJson)

        when: "a JSON response above the configured threshold"
        HttpResponse<byte[]> largeJson = send(server, '/compression/json-large', 'gzip')

        then:
        isGzip(largeJson)
        gunzip(largeJson.body()).contains(LARGE)

        cleanup:
        server.close()
    }

    void "a response without a content type is left alone"() {
        when:
        HttpResponse<byte[]> response = send('/compression/empty', 'gzip')

        then:
        response.statusCode() == 204
        !isGzip(response)
    }

    void "a response without a declared length is compressed by its type alone"() {
        when:
        HttpResponse<byte[]> response = send('/compression/stream', 'gzip')

        then:
        response.statusCode() == 200
        isGzip(response)
        gunzip(response.body()) == LARGE * 3
    }

    private static boolean isGzip(HttpResponse<byte[]> response) {
        response.headers().firstValue(HttpHeaders.CONTENT_ENCODING.toLowerCase()).orElse(null) == 'gzip'
    }

    private HttpResponse<byte[]> send(String path, String acceptEncoding) {
        send(embeddedServer, path, acceptEncoding)
    }

    private static HttpResponse<byte[]> send(EmbeddedServer server, String path, String acceptEncoding) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            client.send(
                HttpRequest.newBuilder(server.getURI().resolve(path))
                    .header('Accept-Encoding', acceptEncoding)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray())
        }
    }

    private static String gunzip(byte[] bytes) {
        new GZIPInputStream(new ByteArrayInputStream(bytes)).text
    }

    @Requires(property = 'spec.name', value = 'UndertowCompressionSpec')
    @Controller('/compression')
    static class CompressionController {

        @Get(value = '/large', produces = MediaType.TEXT_PLAIN)
        String large() {
            LARGE
        }

        @Get(value = '/small', produces = MediaType.TEXT_PLAIN)
        String small() {
            SMALL
        }

        @Get(value = '/json-small', produces = MediaType.APPLICATION_JSON)
        Map<String, String> jsonSmall() {
            [text: 'x' * 1500]
        }

        @Get(value = '/json-large', produces = MediaType.APPLICATION_JSON)
        Map<String, String> jsonLarge() {
            [text: LARGE]
        }

        @Get('/empty')
        io.micronaut.http.HttpResponse<?> empty() {
            io.micronaut.http.HttpResponse.noContent()
        }

        @Get(value = '/stream', produces = MediaType.TEXT_PLAIN)
        Flux<String> stream() {
            Flux.just(LARGE, LARGE, LARGE)
        }
    }
}
