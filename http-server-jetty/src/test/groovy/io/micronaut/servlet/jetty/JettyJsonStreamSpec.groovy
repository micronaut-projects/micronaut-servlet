
package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.Introspected
import io.micronaut.core.async.annotation.SingleResult
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.client.StreamingHttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.runtime.server.EmbeddedServer
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import spock.lang.AutoCleanup
import spock.lang.Issue
import spock.lang.PendingFeature
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.Semaphore
/**
 * Created by graemerocher on 19/01/2018.
 */
class JettyJsonStreamSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext context = ApplicationContext.run([
            'spec.name': 'JettyJsonStreamSpec'
    ])

    @Shared
    EmbeddedServer embeddedServer = context.getBean(EmbeddedServer).start()

    @Shared
    BookClient bookClient = embeddedServer.getApplicationContext().getBean(BookClient)

    static Semaphore signal

    void "test read JSON stream demand all"() {
        given:
        StreamingHttpClient client = context.createBean(StreamingHttpClient, embeddedServer.getURL())

        when:
        List<Map> jsonObjects = Flux.from(client.jsonStream(HttpRequest.GET(
                '/jsonstream/books'
        ))).collectList().block()

        then:
        jsonObjects.size() == 2
        jsonObjects[0].title == 'The Stand'
        jsonObjects[1].title == 'The Shining'

        cleanup:
        client.stop()
    }

    @Issue('https://github.com/micronaut-projects/micronaut-core/issues/1864')
    void "test read JSON stream raw data and demand all"() {
        given:
        StreamingHttpClient client = context.createBean(StreamingHttpClient, embeddedServer.getURL())

        when:
        List<Chunk> jsonObjects = Flux.from(client.jsonStream(
                HttpRequest.POST('/jsonstream/books/raw', '''
{"type":"ADDED"}
{"type":"ADDED"}
{"type":"ADDED"}
{"type":"ADDED"}
''').contentType(MediaType.APPLICATION_JSON_STREAM_TYPE)
                        .accept(MediaType.APPLICATION_JSON_STREAM_TYPE), Chunk)).collectList().block()

        then:
        jsonObjects.size() == 4

        cleanup:
        client.stop()
    }

    void "test read JSON stream demand all POJO"() {
        given:
        StreamingHttpClient client = context.createBean(StreamingHttpClient, embeddedServer.getURL())

        when:
        List<Book> jsonObjects = Flux.from(client.jsonStream(HttpRequest.GET(
                '/jsonstream/books'
        ), Book)).collectList().block()

        then:
        jsonObjects.size() == 2
        jsonObjects.every() { it instanceof Book}
        jsonObjects[0].title == 'The Stand'
        jsonObjects[1].title == 'The Shining'
    }

    void "test read JSON stream demand one"() {
        given:
        StreamingHttpClient client = context.createBean(StreamingHttpClient, embeddedServer.getURL())

        when:
        def stream = client.jsonStream(HttpRequest.GET(
                '/jsonstream/books'
        ))
        Map json

        stream.subscribe(new Subscriber<Map<String, Object>>() {
            @Override
            void onSubscribe(Subscription s) {
                s.request(1)
            }

            @Override
            void onNext(Map<String, Object> stringObjectMap) {
                json = stringObjectMap
            }

            @Override
            void onError(Throwable t) {

            }

            @Override
            void onComplete() {

            }
        })

        PollingConditions conditions = new PollingConditions()
        then:
        conditions.eventually {
            json != null
            json.title == "The Stand"
        }
    }

    void "we can stream books to the server"() {
        given:
        StreamingHttpClient client = context.createBean(StreamingHttpClient, embeddedServer.getURL())
        signal = new Semaphore(1)

        when:
        // Funny request flow which required the server to relase the semaphore so we can keep sending stuff
        def stream = Flux.from(client.jsonStream(HttpRequest.POST(
                '/jsonstream/books/count',
                Mono.fromCallable {
                    JettyJsonStreamSpec.signal.acquire()
                    new Book(title: "Micronaut for dummies")
                }
                        .repeat(9)
        ).contentType(MediaType.APPLICATION_JSON_STREAM_TYPE).accept(MediaType.APPLICATION_JSON_STREAM_TYPE)))

        then:
        stream.timeout(Duration.of(5, ChronoUnit.SECONDS)).blockFirst().bookCount == 10
    }

    @PendingFeature
    void "we can stream data from the server through the generated client"() {
        when:
        List<Book> books = Flux.from(bookClient.list()).collectList().block()

        then:
        books.size() == 2
        books*.title == ['The Stand', 'The Shining']
    }

    void "we can use a generated client to stream books to the server"() {
        given:
        signal = new Semaphore(1)

        when:
        Publisher<LibraryStats> result = bookClient.count(
                Mono.fromCallable {
                    JettyJsonStreamSpec.signal.acquire()
                    new Book(title: "Micronaut for dummies, volume 2")
                }
                        .repeat(6))
        then:
        Mono.from(result).timeout(Duration.ofSeconds(10)).block().bookCount == 7
    }

    void "test returning an empty publisher"() {
        when:
        List<Book> books = Flux.from(bookClient.empty()).collectList().block()

        then:
        noExceptionThrown()
        books.isEmpty()
    }

    @Requires(property = 'spec.name', value = 'JettyJsonStreamSpec')
    @Client("/jsonstream/books")
    static interface BookClient {
        @Get(consumes = MediaType.APPLICATION_JSON_STREAM)
        Publisher<Book> list();

        @Post(uri = "/count", processes = MediaType.APPLICATION_JSON_STREAM)
        @SingleResult
        Publisher<LibraryStats> count(@Body Publisher<Book> theBooks)

        @Get(uri = "/empty", consumes = MediaType.APPLICATION_JSON)
        Publisher<Book> empty();
    }

    @Requires(property = 'spec.name', value = 'JettyJsonStreamSpec')
    @Controller("/jsonstream/books")
    static class BookController {

        @Get(produces = MediaType.APPLICATION_JSON_STREAM)
        Publisher<Book> list() {
            return Flux.just(new Book(title: "The Stand"), new Book(title: "The Shining"))
        }

        // Funny controller which signals the semaphone, causing the the client to send more
        @Post(uri = "/count", processes = MediaType.APPLICATION_JSON_STREAM)
        @SingleResult
        Publisher<LibraryStats> count(@Body Publisher<Book> theBooks) {
            theBooks.map {
                Book b ->
                    JettyJsonStreamSpec.signal.release()
                    b.title
            }.count().map {
                bookCount -> new LibraryStats(bookCount: bookCount)
            }
        }

        @Post(uri = "/raw", processes = MediaType.APPLICATION_JSON_STREAM)
        String rawData(@Body Flux<Chunk> chunks) {
            return chunks
                    .map({ chunk -> "{\"type\":\"${chunk.type}\"}"})
                    .collectList()
                    .map({ chunkList -> "\n" + chunkList.join("\n")})
                    .block()
        }

        @Get(uri = "/empty", produces = MediaType.APPLICATION_JSON)
        Publisher<Book> empty() {
            return Flux.empty()
        }
    }

    @Introspected
    static class Book {
        String title
    }

    @Introspected
    static class LibraryStats {
        Integer bookCount
    }

    @Introspected
    static class Chunk {
        String type
    }

}


