
package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Patch
import io.micronaut.http.annotation.Post
import io.micronaut.http.client.annotation.Client
import io.micronaut.runtime.server.EmbeddedServer
import reactor.core.publisher.Mono
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicLong

/**
 * @author graemerocher
 * @since 1.0
 */
class JettyReactorCrudSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, ['spec.name': 'JettyReactorCrudSpec'])

    void "test it is possible to implement CRUD operations with Reactor"() {
        given:
        BookClient client = embeddedServer.applicationContext.getBean(BookClient)

        when:
        Book book = client.get(99).block()
        List<Book> books = client.list().block()

        then:
        book == null
        books.size() == 0

        when:
        book = client.save("The Stand").block()

        then:
        book != null
        book.title == "The Stand"
        book.id == 1

        when:
        book = client.get(book.id).block()

        then:
        book != null
        book.title == "The Stand"
        book.id == 1


        when:'the full response is resolved'
        HttpResponse<Book> bookAndResponse = client.getResponse(book.id).block()

        then:"The response is valid"
        bookAndResponse.status() == HttpStatus.OK
        bookAndResponse.body().title == "The Stand"

        when:
        book = client.update(book.id, "The Shining").block()

        then:
        book != null
        book.title == "The Shining"
        book.id == 1

        when:
        book = client.delete(book.id).block()

        then:
        book != null

        when:
        book = client.get(book.id).block()

        then:
        book == null
    }

    @Requires(property = 'spec.name', value = 'JettyReactorCrudSpec')
    @Client('/reactor/books')
    static interface BookClient extends BookApi {
    }

    @Requires(property = 'spec.name', value = 'JettyReactorCrudSpec')
    @Controller("/reactor/books")
    static class BookController implements BookApi {

        Map<Long, Book> books = new LinkedHashMap<>()
        AtomicLong currentId = new AtomicLong(0)

        @Override
        Mono<Book> get(Long id) {
            Book book = books.get(id)
            if(book)
                return Mono.just(book)
            Mono.empty()
        }

        @Override
        Mono<HttpResponse<Book>> getResponse(Long id) {
            Book book = books.get(id)
            if(book) {
                return Mono.just(HttpResponse.ok(book))
            }
            return Mono.just(HttpResponse.notFound())
        }

        @Override
        Mono<List<Book>> list() {
            return Mono.just(books.values().toList())
        }

        @Override
        Mono<Book> delete(Long id) {
            Book book = books.remove(id)
            if(book) {
                return Mono.just(book)
            }
            return Mono.empty()
        }

        @Override
        Mono<Book> save(String title) {
            Book book = new Book(title: title, id:currentId.incrementAndGet())
            books[book.id] = book
            return Mono.just(book)
        }

        @Override
        Mono<Book> update(Long id, String title) {
            Book book = books[id]
            if(book != null) {
                book.title = title
                return Mono.just(book)
            }
            else {
                return Mono.empty()
            }
        }
    }

    static interface BookApi {
        @Get("/{id}")
        Mono<Book> get(Long id)

        @Get("/res/{id}")
        Mono<HttpResponse<Book>> getResponse(Long id)

        @Get
        Mono<List<Book>> list()

        @Delete("/{id}")
        Mono<Book> delete(Long id)

        @Post
        Mono<Book> save(String title)

        @Patch("/{id}")
        Mono<Book> update(Long id, String title)
    }

    static class Book {
        Long id
        String title
    }
}
