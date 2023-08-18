package io.micronaut.servlet.tomcat

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.Introspected
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Status
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import spock.lang.Specification
import io.micronaut.http.annotation.Error

@MicronautTest
@Property(name = 'spec.name', value = SPEC_NAME)
class ErrorRebindingSpec extends Specification {

    static final String SPEC_NAME = "LocalErrorReadingBodyTest";

    @Inject
    @Client('/')
    HttpClient client

    void 'rebinding the body to a different type in an error handler works'() throws IOException {
        when:
        def response = client.toBlocking().exchange(HttpRequest.POST('/json/jsonBody','{"numberField": "textInsteadOf'))

        then:
        def ex = thrown(HttpClientResponseException)
        ex.status == HttpStatus.BAD_REQUEST
        ex.response.getBody(String).get() == "Syntax error: {\"numberField\": \"textInsteadOf"
    }


    @Introspected
    static class RequestObject {

        @Min(1L)
        private Integer numberField;

        RequestObject(Integer numberField) {
            this.numberField = numberField;
        }
    }

    @Controller("/json")
    @Requires(property = "spec.name", value = SPEC_NAME)
    static class JsonController {

        @Post("/jsonBody")
        String jsonBody(@Valid @Body RequestObject data) {
            return "blah";
        }

        @Error
        @Status(HttpStatus.BAD_REQUEST)
        String syntaxErrorHandler(@Body @Nullable String body) {
            return "Syntax error: " + body;
        }
    }
}
