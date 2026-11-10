/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.servlet.http;

import io.micronaut.core.annotation.Internal;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a JSON body, a top-level array or a JSON stream, into its nodes at the demand of the subscriber.
 *
 * <p>The reactive parser of the {@link JsonMapper} emits every node of a chunk as it parses it, whatever the
 * demand, and asks for the next chunk itself. It is fed here one chunk at a time instead: the next chunk of the
 * body is read only once the nodes of the previous one were taken, so that at most the nodes of one chunk are
 * held, as the Netty server does.</p>
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
final class JsonChunkParser implements Subscriber<JsonNode>, Subscription {

    private final Processor<byte[], JsonNode> parser;
    // only touched by the parser, from feed and finish, which the chunks run one after the other
    private List<JsonNode> parsed = new ArrayList<>();
    private @Nullable Throwable failure;
    private boolean completed;

    private JsonChunkParser(JsonMapper jsonMapper) {
        this.parser = jsonMapper.createReactiveParser(p -> p.onSubscribe(this), true);
        parser.subscribe(this);
    }

    /**
     * @param jsonMapper The mapper whose reactive parser parses the body
     * @param body The chunks of the body
     * @return The nodes, published at the demand of the subscriber
     */
    static Flux<JsonNode> parse(JsonMapper jsonMapper, Publisher<byte[]> body) {
        return Flux.defer(() -> {
            JsonChunkParser chunkParser = new JsonChunkParser(jsonMapper);
            return Flux.concat(
                Flux.from(body).concatMap(chunkParser::feed, 1),
                Flux.defer(chunkParser::finish)
            );
        });
    }

    private Flux<JsonNode> feed(byte[] chunk) {
        if (failure == null && !completed) {
            parser.onNext(chunk);
        }
        return drain();
    }

    private Flux<JsonNode> finish() {
        if (failure == null && !completed) {
            parser.onComplete();
        }
        return drain();
    }

    private Flux<JsonNode> drain() {
        Throwable error = failure;
        if (error != null) {
            return Flux.error(error);
        }
        if (parsed.isEmpty()) {
            return Flux.empty();
        }
        List<JsonNode> nodes = parsed;
        parsed = new ArrayList<>();
        return Flux.fromIterable(nodes);
    }

    // the parser's subscription to the chunks: they are fed one at a time, whatever it asks for

    @Override
    public void request(long n) {
        // the next chunk is fed once the nodes of the previous one were taken
    }

    @Override
    public void cancel() {
        // the body is cancelled through the subscription of the returned publisher
    }

    // the subscriber of the parser's nodes

    @Override
    public void onSubscribe(Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(JsonNode node) {
        parsed.add(node);
    }

    @Override
    public void onError(Throwable t) {
        failure = t;
    }

    @Override
    public void onComplete() {
        completed = true;
    }
}
