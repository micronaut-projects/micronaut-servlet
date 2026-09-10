package io.micronaut.servlet.jetty.websocket

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.security.authentication.AuthenticationFailed
import io.micronaut.security.authentication.AuthenticationRequest
import io.micronaut.security.authentication.AuthenticationResponse
import io.micronaut.security.authentication.provider.HttpRequestAuthenticationProvider
import io.micronaut.websocket.WebSocketClient
import io.micronaut.websocket.exceptions.WebSocketClientException
import jakarta.inject.Singleton
import org.jspecify.annotations.NonNull
import org.jspecify.annotations.Nullable
import reactor.core.publisher.Flux
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * micronaut-security runs on the handshake because the upgrade request goes through the
 * Micronaut filter chain, so `@Secured` rejects an unauthenticated upgrade and the
 * authenticated identity survives the protocol switch.
 */
class JettyWebSocketSecuritySpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
            'spec.name'                : 'JettyWebSocketSecuritySpec',
            'micronaut.security.enabled': true
    ])

    @Shared
    @AutoCleanup
    WebSocketClient wsClient = embeddedServer.applicationContext.createBean(WebSocketClient, embeddedServer.URI)

    void "an unauthenticated upgrade to a @Secured endpoint is rejected"() {
        when:
        Flux.from(wsClient.connect(ChatReplyClientWebSocket, "/authenticated/chat")).blockFirst()

        then:
        thrown(WebSocketClientException)
    }

    void "an authenticated upgrade succeeds and the principal reaches the handlers"() {
        given:
        PollingConditions conditions = new PollingConditions(timeout: 15, delay: 0.2)

        when:
        ChatReplyClientWebSocket client = Flux.from(wsClient.connect(
                ChatReplyClientWebSocket,
                HttpRequest.GET("/authenticated/chat").basicAuth("sherlock", "password")
        )).blockFirst()
        client.send("hello")

        then: "the principal is bound in @OnOpen and available on the session afterwards"
        conditions.eventually {
            client.replies.contains("sherlock:sherlock:hello")
        }

        cleanup:
        client.close()
    }

    void "bad credentials do not open a socket"() {
        when:
        Flux.from(wsClient.connect(
                ChatReplyClientWebSocket,
                HttpRequest.GET("/authenticated/chat").basicAuth("sherlock", "wrong")
        )).blockFirst()

        then:
        thrown(WebSocketClientException)
    }

    @Requires(property = 'spec.name', value = 'JettyWebSocketSecuritySpec')
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
