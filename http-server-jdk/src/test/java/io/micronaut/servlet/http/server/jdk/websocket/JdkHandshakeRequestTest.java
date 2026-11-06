package io.micronaut.servlet.http.server.jdk.websocket;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdkHandshakeRequestTest {

    @Test
    void theRequestUriIsCompleteWithTheHostOfTheHandshake() {
        URI uri = JdkHandshakeRequest.absolute(URI.create("/chat/a%20b?x=1"), false, "example.com:8080", null);

        assertEquals("ws://example.com:8080/chat/a%20b?x=1", uri.toString());
        assertEquals("ws", uri.getScheme());
        assertEquals("example.com", uri.getHost());
        assertEquals(8080, uri.getPort());
        assertEquals("/chat/a b", uri.getPath());
    }

    @Test
    void aSecureHandshakeIsWss() {
        URI uri = JdkHandshakeRequest.absolute(URI.create("/chat"), true, "example.com", null);

        assertEquals("wss://example.com/chat", uri.toString());
    }

    @Test
    void withoutAValidHostTheLocalAddressIsTheAuthority() {
        InetSocketAddress local = new InetSocketAddress("127.0.0.1", 9000);

        assertEquals("ws://127.0.0.1:9000/chat", JdkHandshakeRequest.absolute(URI.create("/chat"), false, null, local).toString());
        assertEquals("ws://127.0.0.1:9000/chat", JdkHandshakeRequest.absolute(URI.create("/chat"), false, "bad host", local).toString());
        assertEquals("ws://[0:0:0:0:0:0:0:1]:9000/", JdkHandshakeRequest.absolute(URI.create(""), false, null,
            new InetSocketAddress("::1", 9000)).toString());
    }
}
