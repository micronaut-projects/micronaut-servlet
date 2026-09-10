package io.micronaut.servlet.websocket;

import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketSession;
import jakarta.websocket.EndpointConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicronautServerEndpointTest {

    @Test
    void aSessionOpenedWithoutAContextIsClosedRatherThanLeftDangling() {
        TestSession session = new TestSession();

        new MicronautServerEndpoint().onOpen(session, endpointConfig(new HashMap<>()));

        assertFalse(session.open, "an endpoint with no Micronaut context must not stay connected");
        assertEquals(CloseReason.INTERNAL_ERROR.getCode(), session.closeReason.getCloseCode().getCode());
    }

    @Test
    void aValueIsPreBoundToTheFirstArgumentThatAcceptsIt() {
        ExecutableMethod<?, ?> method = methodWithArguments(
            Argument.of(WebSocketSession.class, "session"),
            Argument.of(Throwable.class, "error")
        );
        IllegalStateException cause = new IllegalStateException("boom");

        Map<Argument<?>, Object> preBound = MicronautServerEndpoint.preBind(method, cause);

        assertEquals(1, preBound.size());
        assertEquals("error", preBound.keySet().iterator().next().getName());
        assertSame(cause, preBound.values().iterator().next());
    }

    @Test
    void aValueNoArgumentAcceptsIsNotPreBound() {
        ExecutableMethod<?, ?> method = methodWithArguments(Argument.of(String.class, "message"));

        assertTrue(MicronautServerEndpoint.preBind(method, new IllegalStateException("boom")).isEmpty());
    }

    @Test
    void closeReasonsArePreBoundByTypeWhereverTheyAppear() {
        ExecutableMethod<?, ?> method = methodWithArguments(
            Argument.of(CloseReason.class, "reason"),
            Argument.of(WebSocketSession.class, "session")
        );

        Map<Argument<?>, Object> preBound = MicronautServerEndpoint.preBind(method, CloseReason.GOING_AWAY);

        assertEquals("reason", preBound.keySet().iterator().next().getName());
    }

    private static EndpointConfig endpointConfig(Map<String, Object> userProperties) {
        return (EndpointConfig) Proxy.newProxyInstance(
            MicronautServerEndpointTest.class.getClassLoader(),
            new Class<?>[]{EndpointConfig.class},
            (InvocationHandler) (proxy, method, args) ->
                method.getName().equals("getUserProperties") ? userProperties : List.of());
    }

    private static ExecutableMethod<?, ?> methodWithArguments(Argument<?>... arguments) {
        return (ExecutableMethod<?, ?>) Proxy.newProxyInstance(
            MicronautServerEndpointTest.class.getClassLoader(),
            new Class<?>[]{ExecutableMethod.class},
            (InvocationHandler) (proxy, method, args) ->
                method.getName().equals("getArguments") ? arguments : null);
    }
}
