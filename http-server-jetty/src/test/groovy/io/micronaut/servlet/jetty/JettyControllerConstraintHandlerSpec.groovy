package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.Introspected
import io.micronaut.core.annotation.NonNull
import io.micronaut.core.annotation.Nullable
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Error
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Produces
import io.micronaut.http.annotation.Status
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import spock.lang.Specification

@MicronautTest
@Property(name = "spec.name", value = JettyControllerConstraintHandlerSpec.SPEC_NAME)
class JettyControllerConstraintHandlerSpec extends Specification {

    static final String SPEC_NAME = "JettyControllerConstraintHandlerSpec";

    @Inject
    @Client("/")
    HttpClient client;

    void "happy path"() {
        when:
        HttpRequest<?> post = HttpRequest.POST("/constraints-via-handler", '{"username":"tim@micronaut.example","password":"secret"}')
        def exchange = request(post)

        then:
        exchange.status == HttpStatus.OK
    }

    void "invalid email with @nullable via handler"() {
        when:
        HttpRequest<?> post = HttpRequest.POST("/constraints-via-handler/with-at-nullable", '{"username":"invalidemail","password":"secret"}')
        request(post)

        then:
        def e = thrown(HttpClientResponseException)
        constraintAssertion(e.response, 'must be a well-formed email address')
    }

    void "blank email with @nullable via handler"() {
        when:
        HttpRequest<?> post = HttpRequest.POST("/constraints-via-handler/with-at-nullable", '{"username":"","password":"secret"}')
        request(post)

        then:
        def e = thrown(HttpClientResponseException)
        constraintAssertion(e.response, 'must not be blank')
    }

    void "blank email with @nullable via onError method"() {
        when:
        HttpRequest<?> post = HttpRequest.POST("/constraints-via-on-error-method/with-at-nullable", '{"username":"","password":"secret"}')
        request(post)

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.I_AM_A_TEAPOT
        e.response.getBody(String).get() == '{"password":"secret"}'
    }

    void "invalid email without @nullable via handler"() {
        when:
        HttpRequest<?> post = HttpRequest.POST("/constraints-via-handler", '{"username":"invalidemail","password":"secret"}')
        request(post)

        then:
        def e = thrown(HttpClientResponseException)
        constraintAssertion(e.response, 'must be a well-formed email address')
    }

    void "blank email without @nullable via handler"() {
        when:
        HttpRequest<?> post = HttpRequest.POST("/constraints-via-handler", '{"username":"","password":"secret"}')
        request(post)

        then:
        def e = thrown(HttpClientResponseException)
        constraintAssertion(e.response, 'must not be blank')
    }

    void "blank email without @nullable via onError method"() {
        when:
        HttpRequest<?> post = HttpRequest.POST("/constraints-via-on-error-method", '{"username":"","password":"secret"}')
        request(post)

        then:
        def e = thrown(HttpClientResponseException)
        e.status == HttpStatus.I_AM_A_TEAPOT
        e.response.getBody(String).get() == '{"password":"secret"}'
    }

    private static constraintAssertion(HttpResponse<?> response, String expectedMessage, HttpStatus expectedStatus = HttpStatus.BAD_REQUEST) {
        assert response.status == expectedStatus
        assert response.getBody(Argument.of(Map)).get()._embedded.errors.any { it.message.contains(expectedMessage) }
        true
    }

    def request(HttpRequest<?> req) {
        client.toBlocking().exchange(req)
    }

    @Controller("/constraints-via-handler")
    @Requires(property = "spec.name", value = SPEC_NAME)
    static class BodyController {

        @Post
        @Produces(MediaType.TEXT_PLAIN)
        @Status(HttpStatus.OK)
        void login(@Body @NotNull @Valid CredentialsWithoutNullabilityAnnotation credentials) {
        }

        @Post("/with-at-nullable")
        @Produces(MediaType.TEXT_PLAIN)
        @Status(HttpStatus.OK)
        void login(@Body @NotNull @Valid CredentialsWithNullable credentials) {
        }

        @Post("/with-non-null")
        @Produces(MediaType.TEXT_PLAIN)
        @Status(HttpStatus.OK)
        void login(@Body @NotNull @Valid CredentialsWithNonNull credentials) {
        }
    }

    @Controller("/constraints-via-on-error-method")
    @Requires(property = "spec.name", value = SPEC_NAME)
    static class OnErrorMethodController {

        @Post
        @Produces(MediaType.TEXT_PLAIN)
        @Status(HttpStatus.OK)
        void login(@Body @NotNull @Valid CredentialsWithoutNullabilityAnnotation credentials) {
        }

        @Post("/with-at-nullable")
        @Produces(MediaType.TEXT_PLAIN)
        @Status(HttpStatus.OK)
        void loginWithNullable(@Body @NotNull @Valid CredentialsWithNullable credentials) {
        }

        @Post("/with-non-null")
        @Produces(MediaType.TEXT_PLAIN)
        @Status(HttpStatus.OK)
        void loginWithNullable(@Body @NotNull @Valid CredentialsWithNonNull credentials) {
        }

        @Error(exception = ConstraintViolationException.class)
        @Status(HttpStatus.I_AM_A_TEAPOT)
        Optional<Map> constraintsEx(ConstraintViolationException e, HttpRequest<?> request) {

            Optional<?> objectOptional = request.getBody();
            if (objectOptional.isEmpty()) {
                return Optional.empty();
            }
            Object obj = objectOptional.get();
            String password = null;
            if (obj instanceof CredentialsWithoutNullabilityAnnotation) {
                password = ((CredentialsWithoutNullabilityAnnotation)obj).password
            } else if (obj instanceof CredentialsWithNullable) {
                password = ((CredentialsWithNullable)obj).password
            } else if (obj instanceof CredentialsWithNonNull) {
                password = ((CredentialsWithNonNull)obj).password
            }
            password != null ? Optional.of(Map.of("password", password)) : Optional.<Map>empty();
        }
    }

    @Introspected
    static class CredentialsWithoutNullabilityAnnotation {

        @NotBlank
        @Email
        private final String username;

        @NotBlank
        private final String password;

        CredentialsWithoutNullabilityAnnotation(String username, String password) {
            this.username = username
            this.password = password
        }

        String getUsername() {
            username
        }

        String getPassword() {
            password
        }
    }

    @Introspected
    static class CredentialsWithNullable {

        @NotBlank
        @Email
        @Nullable
        private final String username

        @NotBlank
        @Nullable
        private final String password

        CredentialsWithNullable(@Nullable String username, @Nullable String password) {
            this.username = username
            this.password = password
        }

        @Nullable
        String getUsername() {
            username
        }

        @Nullable
        String getPassword() {
            password
        }
    }

    @Introspected
    static class CredentialsWithNonNull {
        @NotBlank
        @Email
        @NonNull
        private final String username

        @NotBlank
        @NonNull
        private final String password

        CredentialsWithNonNull(@NonNull String username, @NonNull String password) {
            this.username = username
            this.password = password
        }

        @NonNull
        String getUsername() {
            username
        }

        @NonNull
        String getPassword() {
            password
        }
    }
}
