package io.micronaut.servlet.tomcat;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.async.annotation.SingleResult;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.spock.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import spock.lang.Specification;

@Property(name = "spec.name", value = "RenderJsonTest")
@MicronautTest
class RenderJsonSpec extends Specification  {

    @Inject
    @Client("/")
    HttpClient httpClient;

    void "test rendering a publisher JSON payload works"() {
        given:
        BlockingHttpClient client = httpClient.toBlocking()

        when:
        HttpResponse<Map> response = client.exchange(HttpRequest.GET("/login"), Map)

        then:
        noExceptionThrown()
        response.getBody().isPresent()
        response.getBody().get().containsKey("access_token")
    }

    @Requires(property = "spec.name", value = "RenderJsonTest")
    @Controller("/login")
    static class LoginController {
        @Get
        @SingleResult
        Publisher<MutableHttpResponse<?>> login() {
            Mono.just("{\"username\":\"john\",\"access_token\":\"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJqb2huIiwibmJmIjoxNjYwODE4MjM4LCJyb2xlcyI6W10sImlzcyI6Im1pY3JvbmF1dCIsImV4cCI6MTY2MDgyMTgzOCwiaWF0IjoxNjYwODE4MjM4fQ.4UrbjEKy4ts3t03z9WJVb1urdeq_sMkO17iwEKURF_8\",\"token_type\":\"Bearer\",\"expires_in\":3600}")
            .map(HttpResponse::ok)
        }
    }
}
