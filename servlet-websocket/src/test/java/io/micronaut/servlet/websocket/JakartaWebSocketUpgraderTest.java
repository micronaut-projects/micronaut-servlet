package io.micronaut.servlet.websocket;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.websocket.annotation.ServerWebSocket;
import io.micronaut.websocket.context.WebSocketBean;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JakartaWebSocketUpgraderTest {

    @Test
    void anAbsentSubprotocolsMemberYieldsNoSubprotocols() {
        assertTrue(JakartaWebSocketUpgrader.subprotocols(webSocketBean(null)).isEmpty());
        assertTrue(JakartaWebSocketUpgrader.subprotocols(webSocketBean("")).isEmpty());
    }

    @Test
    void theSubprotocolsMemberIsSplitAndTrimmed() {
        assertEquals(List.of("chat", "superchat"), JakartaWebSocketUpgrader.subprotocols(webSocketBean("chat, superchat")));
        assertEquals(List.of("chat"), JakartaWebSocketUpgrader.subprotocols(webSocketBean("chat,,")));
    }

    @Test
    void aRuntimeWithoutAServerContainerReportsNotImplemented() {
        HttpStatusException e = assertThrows(
            HttpStatusException.class,
            () -> JakartaWebSocketUpgrader.resolveServerContainer(requestWithContainerAttribute(null))
        );

        assertEquals(HttpStatus.NOT_IMPLEMENTED, e.getStatus());
        assertTrue(e.getMessage().contains("ServerContainer"));
    }

    /**
     * A bean definition that answers only the {@code subprotocols} member, which is all the
     * upgrader reads from it.
     */
    private static WebSocketBean<?> webSocketBean(String subprotocols) {
        BeanDefinition<?> definition = (BeanDefinition<?>) Proxy.newProxyInstance(
            JakartaWebSocketUpgraderTest.class.getClassLoader(),
            new Class<?>[]{BeanDefinition.class},
            (InvocationHandler) (proxy, method, args) -> {
                if (method.getName().equals("stringValue")
                    && args != null && args.length == 2
                    && args[0] == ServerWebSocket.class
                    && "subprotocols".equals(args[1])) {
                    return Optional.ofNullable(subprotocols);
                }
                if (method.getReturnType() == Optional.class) {
                    return Optional.empty();
                }
                if (method.getReturnType() == boolean.class) {
                    return false;
                }
                return null;
            });
        return (WebSocketBean<?>) Proxy.newProxyInstance(
            JakartaWebSocketUpgraderTest.class.getClassLoader(),
            new Class<?>[]{WebSocketBean.class},
            (InvocationHandler) (proxy, method, args) ->
                method.getName().equals("getBeanDefinition") ? definition : null);
    }

    private static HttpServletRequest requestWithContainerAttribute(Object attribute) {
        ServletContext servletContext = (ServletContext) Proxy.newProxyInstance(
            JakartaWebSocketUpgraderTest.class.getClassLoader(),
            new Class<?>[]{ServletContext.class},
            (InvocationHandler) (proxy, method, args) ->
                method.getName().equals("getAttribute") ? attribute : null);
        return (HttpServletRequest) Proxy.newProxyInstance(
            JakartaWebSocketUpgraderTest.class.getClassLoader(),
            new Class<?>[]{HttpServletRequest.class},
            (InvocationHandler) (proxy, method, args) ->
                method.getName().equals("getServletContext") ? servletContext : null);
    }
}
