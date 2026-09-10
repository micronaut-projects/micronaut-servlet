package io.micronaut.servlet.http.websocket

import io.micronaut.http.HttpRequest
import spock.lang.Specification
import spock.lang.Unroll

class ServletWebSocketUpgraderSpec extends Specification {

    @Unroll
    void "isWebSocketUpgrade is #expected for #description"() {
        given:
        HttpRequest<?> request = HttpRequest.create(method, "/chat")
        headers.each { name, value -> request.header(name, value) }

        expect:
        ServletWebSocketUpgrader.isWebSocketUpgrade(request) == expected

        where:
        description                       | method                          | headers                                                        || expected
        "a well formed upgrade"           | io.micronaut.http.HttpMethod.GET | ["Connection": "Upgrade", "Upgrade": "websocket"]              || true
        "a mixed case upgrade"            | io.micronaut.http.HttpMethod.GET | ["Connection": "upgrade", "Upgrade": "WebSocket"]              || true
        "a multi valued Connection"       | io.micronaut.http.HttpMethod.GET | ["Connection": "keep-alive, Upgrade", "Upgrade": "websocket"]  || true
        "a spaced Connection list"        | io.micronaut.http.HttpMethod.GET | ["Connection": "keep-alive,  Upgrade", "Upgrade": "websocket"] || true
        "no headers at all"               | io.micronaut.http.HttpMethod.GET | [:]                                                            || false
        "a missing Upgrade header"        | io.micronaut.http.HttpMethod.GET | ["Connection": "Upgrade"]                                      || false
        "a missing Connection header"     | io.micronaut.http.HttpMethod.GET | ["Upgrade": "websocket"]                                       || false
        "a Connection without the token"  | io.micronaut.http.HttpMethod.GET | ["Connection": "keep-alive", "Upgrade": "websocket"]           || false
        "an upgrade to another protocol"  | io.micronaut.http.HttpMethod.GET | ["Connection": "Upgrade", "Upgrade": "h2c"]                    || false
        "a POST rather than a GET"        | io.micronaut.http.HttpMethod.POST| ["Connection": "Upgrade", "Upgrade": "websocket"]              || false
    }
}
