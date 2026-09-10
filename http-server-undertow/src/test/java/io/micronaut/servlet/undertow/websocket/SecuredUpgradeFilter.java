package io.micronaut.servlet.undertow.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import org.jspecify.annotations.Nullable;

@Requires(property = "spec.name", value = "UndertowWebSocketSpec")
@ServerFilter("/**")
public class SecuredUpgradeFilter {

    private volatile boolean invoked;

    @RequestFilter
    public @Nullable HttpResponse<?> filterRequest(HttpRequest<?> request) {
        this.invoked = true;
        if (request.getPath().startsWith("/secured/chat")) {
            return HttpResponse.unauthorized();
        }
        return null;
    }

    @ResponseFilter
    public void filterResponse(MutableHttpResponse<?> response) {
        response.header("X-Filtered", "true");
    }

    public boolean isInvoked() {
        return invoked;
    }

    public void reset() {
        this.invoked = false;
    }
}
