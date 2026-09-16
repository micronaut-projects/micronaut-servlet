package io.micronaut.servlet.undertow.websocket.jakarta;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Proves the Micronaut filter chain still guards a Jakarta endpoint's handshake.
 */
@Requires(property = "spec.name", value = "UndertowJakartaWebSocketSpec")
@ServerFilter("/jakarta/**")
public class JakartaUpgradeFilter {

    public static final AtomicInteger INVOCATIONS = new AtomicInteger();

    @RequestFilter
    public @Nullable HttpResponse<?> filterRequest(HttpRequest<?> request) {
        INVOCATIONS.incrementAndGet();
        if (request.getPath().endsWith("/private")) {
            return HttpResponse.unauthorized();
        }
        return null;
    }
}
