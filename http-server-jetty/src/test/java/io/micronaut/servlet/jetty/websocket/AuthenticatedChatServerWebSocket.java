package io.micronaut.servlet.jetty.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;

import java.security.Principal;

/**
 * Exercises route authorization on the handshake: the upgrade must be rejected without
 * credentials, and the authenticated identity must survive the protocol switch.
 */
@Requires(property = "spec.name", value = "JettyWebSocketSecuritySpec")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ServerWebSocket("/authenticated/chat")
public class AuthenticatedChatServerWebSocket {

    private String openPrincipal;

    @OnOpen
    public void onOpen(Principal principal) {
        this.openPrincipal = principal != null ? principal.getName() : null;
    }

    @OnMessage
    public void onMessage(String message, WebSocketSession session) {
        String sessionPrincipal = session.getUserPrincipal().map(Principal::getName).orElse("<none>");
        session.sendSync(openPrincipal + ":" + sessionPrincipal + ":" + message);
    }
}
