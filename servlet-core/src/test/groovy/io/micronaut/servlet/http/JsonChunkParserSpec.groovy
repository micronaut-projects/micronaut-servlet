package io.micronaut.servlet.http

import io.micronaut.context.ApplicationContext
import io.micronaut.json.JsonMapper
import org.reactivestreams.Subscription
import reactor.core.publisher.BaseSubscriber
import reactor.core.publisher.Flux
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class JsonChunkParserSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext context = ApplicationContext.run()

    @Shared
    JsonMapper jsonMapper = context.getBean(JsonMapper)

    void "the nodes of a chunk are published at the demand of the subscriber, and the next chunk is read only once they are taken"() {
        given:
        AtomicInteger read = new AtomicInteger()
        Flux<byte[]> body = Flux.fromIterable(['[{"n":1},{"n":2},{"n":3}', ',{"n":4},{"n":5}', ']'])
                .map { read.incrementAndGet(); it.getBytes(StandardCharsets.UTF_8) }
        Collector collector = new Collector()

        when:
        values(body).subscribe(collector)

        then: "one node is taken: the first chunk is parsed and the second is read ahead, but not the third"
        collector.received == [1]
        read.get() == 2
        !collector.completed

        when:
        collector.request(2)

        then: "the first chunk's nodes are taken: the second chunk is parsed and the third is read ahead"
        collector.received == [1, 2, 3]
        read.get() == 3
        !collector.completed

        when:
        collector.request(Long.MAX_VALUE)

        then:
        collector.received == [1, 2, 3, 4, 5]
        read.get() == 3
        collector.completed
    }

    void "a JSON stream is parsed value by value"() {
        expect:
        values(chunks('{"n":1}\n{"n"', ':2}\n{"n":3}')).collectList().block() == [1, 2, 3]
    }

    void "an empty body has no node"() {
        expect:
        values(Flux.empty()).collectList().block() == []
    }

    void "an array the body does not close fails at its end"() {
        when:
        values(chunks('[{"n":1},', '{"n":2}')).collectList().block()

        then:
        thrown(Exception)
    }

    void "malformed JSON fails"() {
        when:
        values(chunks('[{"n":1},', '{"n":}]')).collectList().block()

        then:
        thrown(Exception)
    }

    private Flux<Integer> values(Flux<byte[]> body) {
        JsonChunkParser.parse(jsonMapper, body).map { it.get('n').getIntValue() }
    }

    private static Flux<byte[]> chunks(String... chunks) {
        Flux.fromArray(chunks).map { it.getBytes(StandardCharsets.UTF_8) }
    }

    static class Collector extends BaseSubscriber<Integer> {
        final List<Integer> received = []
        boolean completed

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            subscription.request(1)
        }

        @Override
        protected void hookOnNext(Integer value) {
            received << value
        }

        @Override
        protected void hookOnComplete() {
            completed = true
        }
    }
}
