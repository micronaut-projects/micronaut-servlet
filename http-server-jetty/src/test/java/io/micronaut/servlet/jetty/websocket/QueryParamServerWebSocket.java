package io.micronaut.servlet.jetty.websocket;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;

@Requires(property = "spec.name", value = "JettyWebSocketSpec")
@ServerWebSocket("/charity")
public class QueryParamServerWebSocket {

    private String dinner;
    private String customHeader;
    private String requestPath;
    private boolean secure;

    @OnOpen
    public void onOpen(@QueryValue String dinner,
                       @Header("X-Guest") String customHeader,
                       HttpRequest<?> request,
                       WebSocketSession session) {
        this.dinner = dinner;
        this.customHeader = customHeader;
        this.requestPath = request.getPath();
        this.secure = session.isSecure();
    }

    @OnMessage
    public void onMessage(String message, WebSocketSession session) {
        session.sendSync(dinner + ":" + customHeader + ":" + requestPath + ":" + secure);
    }

    public String getDinner() {
        return dinner;
    }

    public String getCustomHeader() {
        return customHeader;
    }
}
