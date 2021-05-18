package io.micronaut.servlet.undertow

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces
import io.micronaut.http.client.RxHttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.AuthenticationException
import io.micronaut.security.authentication.AuthenticationFailed
import io.micronaut.security.authentication.AuthenticationProvider
import io.micronaut.security.authentication.AuthenticationRequest
import io.micronaut.security.authentication.AuthenticationResponse
import io.micronaut.security.authentication.UserDetails
import io.micronaut.security.rules.SecurityRule
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import org.reactivestreams.Publisher
import spock.lang.Issue
import spock.lang.Specification

import javax.inject.Inject
import javax.inject.Singleton
import java.security.Principal

@Issue('https://github.com/micronaut-projects/micronaut-core/issues/5395')
@MicronautTest
@Property(name = 'micronaut.security.enabled', value = 'true')
@Property(name = 'spec.name', value = 'UndertowPrincipalBindingSpec')
class UndertowPrincipalBindingSpec extends Specification {

    @Inject
    @Client('/')
    RxHttpClient client;

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
    static class AuthenticationProviderUserPassword implements AuthenticationProvider {
        @Override
        Publisher<AuthenticationResponse> authenticate(HttpRequest<?> httpRequest, AuthenticationRequest<?, ?> authenticationRequest) {
            Flowable.create({ emitter ->
                String identity = authenticationRequest.identity
                if (identity == 'sherlock' && authenticationRequest.secret == 'password') {
                    emitter.onNext(new UserDetails(identity, []))
                    emitter.onComplete()
                } else {
                    emitter.onError(new AuthenticationException(new AuthenticationFailed()))
                }

            }, BackpressureStrategy.ERROR) as Publisher<AuthenticationResponse>
        }
    }
}
