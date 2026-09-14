package io.micronaut.servlet.undertow

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.sse.SseClient
import io.micronaut.http.sse.Event
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import spock.lang.Specification

/**
 * Server-sent events over a servlet container.
 */
@MicronautTest
@Property(name = 'spec.name', value = 'UndertowServerSentEventsSpec')
class UndertowServerSentEventsSpec extends Specification {

    @Inject
    @Client('/')
    SseClient sseClient

    void "an event stream is delivered as discrete events"() {
        when:
        List<Event<String>> events = Flux.from(sseClient.eventStream(HttpRequest.GET('/sse/events'), String)).collectList().block()

        then:
        events.size() == 3
        events*.data == ['one', 'two', 'three']
    }

    void "event names and ids survive"() {
        when:
        List<Event<String>> events = Flux.from(sseClient.eventStream(HttpRequest.GET('/sse/rich'), String)).collectList().block()

        then:
        events.size() == 2
        events[0].name == 'greeting'
        events[0].id == '1'
        events[0].data == 'hello'
        events[1].name == 'farewell'
        events[1].data == 'goodbye'
    }

    @Requires(property = 'spec.name', value = 'UndertowServerSentEventsSpec')
    @Controller('/sse')
    static class SseController {

        @Get(value = '/events', produces = MediaType.TEXT_EVENT_STREAM)
        Publisher<Event<String>> events() {
            Flux.just('one', 'two', 'three').map(Event::of)
        }

        @Get(value = '/rich', produces = MediaType.TEXT_EVENT_STREAM)
        Publisher<Event<String>> rich() {
            Flux.just(
                Event.of('hello').name('greeting').id('1'),
                Event.of('goodbye').name('farewell').id('2')
            )
        }
    }
}
