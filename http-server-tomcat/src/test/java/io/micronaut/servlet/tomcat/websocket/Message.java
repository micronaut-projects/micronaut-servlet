package io.micronaut.servlet.tomcat.websocket;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public class Message {

    private String text;

    public Message() {
    }

    public Message(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Message other && java.util.Objects.equals(text, other.text);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(text);
    }

    @Override
    public String toString() {
        return "Message(" + text + ")";
    }
}
