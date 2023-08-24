package io.micronaut.servlet.tomcat

import groovy.transform.Canonical
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.AnnotationMetadata
import io.micronaut.core.annotation.Introspected
import io.micronaut.core.annotation.NonNull
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.servlet.http.ServletExchange
import io.micronaut.servlet.http.ServletHttpResponse
import io.micronaut.servlet.http.ServletResponseEncoder
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import spock.lang.Specification

@MicronautTest
@Property(name = "spec.name", value = SPEC_NAME)
@Property(name = "micronaut.server.testing.async", value = "false")
class TomcatResponseEncoderSpec extends Specification {

    private static final String SPEC_NAME = "JettyResponseEncoderSpec"

    @Inject
    @Client("/")
    HttpClient client

    void "custom encoder applied once"() {
        when:
        def response = client.toBlocking().exchange("/test", String)

        then:
        response.body() == "SRE{bar}"
    }

    @Requires(property = "spec.name", value = SPEC_NAME)
    @Controller
    static class TestController {

        @Get("/test")
        SomeResponseType test() {
            new SomeResponseType(foo: "bar")
        }
    }

    @Canonical
    @Introspected
    static class SomeResponseType {
        String foo

        @Override
        String toString() {
            "NOPE!"
        }
    }

    @Requires(property = "spec.name", value = SPEC_NAME)
    @Singleton
    static class SomeResponseEncoder implements ServletResponseEncoder<SomeResponseType> {
        @Override
        Class<SomeResponseType> getResponseType() {
            return SomeResponseType.class
        }

        @Override
        Publisher<MutableHttpResponse<?>> encode(@NonNull ServletExchange<?, ?> exchange, AnnotationMetadata annotationMetadata, @NonNull SomeResponseType value) {
            ServletHttpResponse<?, ?> response = exchange.getResponse().contentType("text/plain")
            response.getOutputStream() << "SRE{$value.foo}"
            Flux.just(response)
        }
    }
}
