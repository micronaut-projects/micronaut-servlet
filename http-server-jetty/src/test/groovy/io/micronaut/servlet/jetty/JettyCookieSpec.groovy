package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Filter
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.cookie.Cookies
import io.micronaut.http.filter.HttpServerFilter
import io.micronaut.http.filter.ServerFilterChain
import io.micronaut.test.annotation.MockBean
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import org.reactivestreams.Publisher
import spock.lang.Issue
import spock.lang.Specification

import jakarta.inject.Inject

@Property(name = 'spec.name', value = 'JettyCookieSpec')
@MicronautTest
class JettyCookieSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    @Issue('https://github.com/micronaut-projects/micronaut-servlet/issues/52')
    void 'test cookies are not null when no cookie is set on request'() {
        given:
        HttpRequest request = HttpRequest.GET('/cookie')

        when:
        HttpResponse response = client.toBlocking().exchange(request)

        then:
        noExceptionThrown()
        response.status == HttpStatus.ACCEPTED
    }

    @Requires(property = 'spec.name', value = 'JettyCookieSpec')
    @Controller('/cookie')
    @MockBean
    static class StatusController {
        @Get
        HttpStatus index() {
            return HttpStatus.ACCEPTED
        }
    }

    @Requires(property = 'spec.name', value = 'JettyCookieSpec')
    @Filter('/cookie/**')
    @MockBean
    static class MyFilter implements HttpServerFilter {

        @Override
        Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
            Cookies cookies = request.cookies

            if (cookies.isEmpty()) {
                println 'Cookies are empty!'
            }

            return chain.proceed(request)
        }
    }

}
