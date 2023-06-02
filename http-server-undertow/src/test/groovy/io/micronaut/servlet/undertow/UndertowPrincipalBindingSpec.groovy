package io.micronaut.servlet.undertow

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
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.authentication.AuthenticationException
import io.micronaut.security.authentication.AuthenticationFailed
import io.micronaut.security.authentication.AuthenticationProvider
import io.micronaut.security.authentication.AuthenticationRequest
import io.micronaut.security.authentication.AuthenticationResponse
import io.micronaut.security.rules.SecurityRule
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import reactor.core.publisher.MonoSink
import spock.lang.Issue
import spock.lang.Specification

import java.security.Principal

@Issue('https://github.com/micronaut-projects/micronaut-core/issues/5395')
@MicronautTest
@Property(name = 'micronaut.security.enabled', value = 'true')
@Property(name = 'spec.name', value = 'UndertowPrincipalBindingSpec')
class UndertowPrincipalBindingSpec extends Specification {

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

    @Requires(property = 'spec.name', value = 'UndertowPrincipalBindingSpec')
    @Controller('/principal')
    static class DemoController {

        @Produces(MediaType.TEXT_PLAIN)
        @Secured(SecurityRule.IS_AUTHENTICATED)
        @Get
        String index(Principal principal) {
            principal.name
        }
    }

    @Requires(property = 'spec.name', value = 'UndertowPrincipalBindingSpec')
    @Singleton
    static class AuthenticationProviderUserPassword<T> implements AuthenticationProvider<T> {
        @Override
        Publisher<AuthenticationResponse> authenticate(T httpRequest, AuthenticationRequest<?, ?> authenticationRequest) {
            Mono.create({ MonoSink emitter ->
                String identity = authenticationRequest.identity
                if (identity == 'sherlock' && authenticationRequest.secret == 'password') {
                    emitter.success(AuthenticationResponse.success(identity))
                } else {
                    emitter.error(new AuthenticationException(new AuthenticationFailed()))
                }

            }) as Publisher<AuthenticationResponse>
        }
    }
}
