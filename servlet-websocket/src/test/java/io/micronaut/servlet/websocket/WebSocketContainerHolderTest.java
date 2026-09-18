package io.micronaut.servlet.websocket;

import io.micronaut.websocket.exceptions.WebSocketException;
import jakarta.servlet.ServletContext;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketContainerHolderTest {

    @Test
    void theContainerAnInitializerRegisteredIsUsed() {
        WebSocketContainerHolder holder = new WebSocketContainerHolder(null);
        TestContainer registered = new TestContainer();

        holder.register(registered);

        assertSame(registered, holder.get());
    }

    @Test
    void theServletContextAttributeIsUsedWhenNothingWasRegistered() {
        TestContainer published = new TestContainer();
        ServletContext servletContext = (ServletContext) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {ServletContext.class},
            (InvocationHandler) (proxy, method, args) ->
                method.getName().equals("getAttribute") && WebSocketContainerHolder.SERVER_CONTAINER_ATTRIBUTE.equals(args[0])
                    ? published
                    : null);
        WebSocketContainerHolder holder = new WebSocketContainerHolder(servletContext);

        assertSame(published, holder.get());
        assertSame(published, holder.get(), "resolved once");
    }

    @Test
    void withNoImplementationAtAllTheFailureNamesTheFix() {
        WebSocketContainerHolder holder = new WebSocketContainerHolder(null);

        // Only the API is on this test classpath, so ContainerProvider finds no implementation.
        WebSocketException e = assertThrows(WebSocketException.class, holder::get);

        assertTrue(e.getMessage().contains("No Jakarta WebSocket container is available"));
        WebSocketContainer registered = new TestContainer();
        holder.register(registered);
        assertSame(registered, holder.get(), "a container registered later is picked up");
    }
}
