package io.micronaut.servlet.jetty

import groovy.transform.InheritConstructors
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.async.publisher.Publishers
import io.micronaut.http.HttpAttributes
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Filter
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.filter.OncePerRequestHttpServerFilter
import io.micronaut.http.filter.ServerFilterChain
import io.micronaut.http.server.exceptions.ExceptionHandler
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import org.reactivestreams.Publisher
import spock.lang.Specification

import javax.inject.Inject
import javax.inject.Singleton

@MicronautTest
@Property(name = "spec.name", value = "JettyExceptionHandlerSpec")
class JettyExceptionHandlerSpec extends Specification {

    @Inject
    @Client("/")
    HttpClient client

    void "test an exception handler for mutable request works"() {
        when:
        def resp = client.toBlocking().exchange("/exception")

        then:
        resp.status() == HttpStatus.OK
    }

    void "test an exception handler returning the body"() {
        when:
        client.toBlocking().retrieve("/exception/my")

        then:
        def ex = thrown(HttpClientResponseException)
        ex.response.status() == HttpStatus.INTERNAL_SERVER_ERROR
        ex.response.getBody().get() == "hello"
    }

    void "test a filter throwing an exception on a 404"() {
        when:
        client.toBlocking().retrieve("/exception/doesnt-exist")

        then:
        def ex = thrown(HttpClientResponseException)
        ex.response.status() == HttpStatus.UNAUTHORIZED
    }

    @Controller("/exception")
    static class ExceptionController {

        @Get
        void throwsEx() {
            throw new RuntimeException("bad")
        }

        @Get("/my")
        void throwsMy() {
            throw new MyException("bad")
        }
    }

    @Singleton
    static class MyExceptionHandler implements ExceptionHandler<MyException, String> {
        @Override
        String handle(HttpRequest request, MyException exception) {
            "hello"
        }
    }

    @Singleton
    @Requires(property = "spec.name", value = "JettyExceptionHandlerSpec")
    static class RuntimeExceptionHandler implements ExceptionHandler<RuntimeException, MutableHttpResponse<?>> {
        @Override
        MutableHttpResponse<?> handle(HttpRequest request, RuntimeException exception) {
            return HttpResponse.ok()
        }
    }

    @InheritConstructors
    static class MyException extends Exception {}

    @Singleton
    @Filter("/**")
    @Requires(property = "spec.name", value = "JettyExceptionHandlerSpec")
    static class MyFilter extends OncePerRequestHttpServerFilter {

        @Override
        protected Publisher<MutableHttpResponse<?>> doFilterOnce(HttpRequest<?> request, ServerFilterChain chain) {
            if (!request.getAttribute(HttpAttributes.ROUTE_MATCH).isPresent()) {
                return Publishers.just(new Unauthorized())
            }
            chain.proceed(request)
        }
    }

    @InheritConstructors
    static class Unauthorized extends Exception {}

    @Singleton
    @Requires(property = "spec.name", value = "JettyExceptionHandlerSpec")
    static class UnauthorizedExceptionHandler implements ExceptionHandler<Unauthorized, MutableHttpResponse<?>> {
        @Override
        MutableHttpResponse<?> handle(HttpRequest request, Unauthorized exception) {
            return HttpResponse.unauthorized()
        }
    }
}
