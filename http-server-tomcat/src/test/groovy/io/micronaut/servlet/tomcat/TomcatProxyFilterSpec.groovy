package io.micronaut.servlet.tomcat

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MutableHttpRequest
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Filter
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.ProxyHttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.filter.HttpServerFilter
import io.micronaut.http.filter.ServerFilterChain
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import spock.lang.Specification

/**
 * A filter that proxies the request with ProxyHttpClient hands back the upstream response as a Netty content
 * stream rather than an object body. The servlet runtime has to forward those bytes, however large the upstream
 * body is (micronaut-core#9725).
 */
@MicronautTest
@Property(name = 'spec.name', value = 'TomcatProxyFilterSpec')
class TomcatProxyFilterSpec extends Specification {

    static final String LARGE = 'proxied payload chunk. ' * 20_000

    @Inject
    @Client('/')
    HttpClient client

    void "a proxied response keeps its body and headers"() {
        when:
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET('/proxied/small'), String)

        then:
        response.status == HttpStatus.OK
        response.body() == 'upstream'
        response.header('X-Proxied') == 'true'
        response.header('X-Upstream') == 'yes'
    }

    void "a large proxied body is streamed through in full"() {
        when:
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET('/proxied/large'), String)

        then:
        response.status == HttpStatus.OK
        response.body().length() == LARGE.length()
        response.body() == LARGE
    }

    void "an upstream error status is forwarded as is"() {
        when:
        client.toBlocking().exchange(HttpRequest.GET('/proxied/missing'), String)

        then:
        def e = thrown(io.micronaut.http.client.exceptions.HttpClientResponseException)
        e.status == HttpStatus.NOT_FOUND
        e.response.header('X-Proxied') == 'true'
    }

    @Requires(property = 'spec.name', value = 'TomcatProxyFilterSpec')
    @Controller('/upstream')
    static class UpstreamController {

        @Get('/small')
        HttpResponse<String> small() {
            HttpResponse.ok('upstream').header('X-Upstream', 'yes')
        }

        @Get('/large')
        String large() {
            LARGE
        }
    }

    @Requires(property = 'spec.name', value = 'TomcatProxyFilterSpec')
    @Filter('/proxied/**')
    static class ProxyingFilter implements HttpServerFilter {
        private final ProxyHttpClient proxyClient
        private final EmbeddedServer embeddedServer

        ProxyingFilter(ProxyHttpClient proxyClient, EmbeddedServer embeddedServer) {
            this.proxyClient = proxyClient
            this.embeddedServer = embeddedServer
        }

        @Override
        Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
            String target = request.path.replace('/proxied', '/upstream')
            MutableHttpRequest<?> upstream = request.mutate().uri { b ->
                b.scheme(embeddedServer.scheme).host(embeddedServer.host).port(embeddedServer.port).replacePath(target)
            }
            Flux.from(proxyClient.proxy(upstream)).map { r -> r.header('X-Proxied', 'true') }
        }
    }
}
