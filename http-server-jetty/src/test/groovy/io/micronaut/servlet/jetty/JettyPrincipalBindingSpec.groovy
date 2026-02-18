package io.micronaut.servlet.jetty

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.AuthenticationFailed
import io.micronaut.security.authentication.AuthenticationRequest
import io.micronaut.security.authentication.AuthenticationResponse
import io.micronaut.security.authentication.provider.HttpRequestAuthenticationProvider
import io.micronaut.security.rules.SecurityRule
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.jspecify.annotations.NonNull
import org.jspecify.annotations.Nullable
import spock.lang.Issue
import spock.lang.Specification

import java.security.Principal

@Issue('https://github.com/micronaut-projects/micronaut-core/issues/5395')
@MicronautTest
@Property(name = 'micronaut.security.enabled', value = 'true')
@Property(name = 'spec.name', value = 'JettyPrincipalBindingSpec')
class JettyPrincipalBindingSpec extends Specification {

    @Inject
    @Client('/')
    HttpClient client;

    void 'test that Principal binds in a secured method'() {
        when:
        def request = HttpRequest.GET('/principal').basicAuth('sherlock', 'password')
        def response = client.toBlocking().exchange(request, String)

        then:
        response.status == HttpStatus.OK
        response.body() == 'sherlock'
    }

    @Requires(property = 'spec.name', value = 'JettyPrincipalBindingSpec')
    @Controller('/principal')
    static class DemoController {

        @Produces(MediaType.TEXT_PLAIN)
        @Secured(SecurityRule.IS_AUTHENTICATED)
        @Get
        String index(Principal principal) {
            principal.name
        }
    }

    @Requires(property = 'spec.name', value = 'JettyPrincipalBindingSpec')
    @Singleton
    static class AuthenticationProviderUserPassword<T> implements HttpRequestAuthenticationProvider<T> {

        @Override
        AuthenticationResponse authenticate(@Nullable HttpRequest<T> requestContext,
                                            @NonNull AuthenticationRequest<String, String> authenticationRequest) {
            String identity = authenticationRequest.identity
            (identity == 'sherlock' && authenticationRequest.secret == 'password')
                    ? AuthenticationResponse.success(identity)
                    : new AuthenticationFailed()
        }
    }
}
