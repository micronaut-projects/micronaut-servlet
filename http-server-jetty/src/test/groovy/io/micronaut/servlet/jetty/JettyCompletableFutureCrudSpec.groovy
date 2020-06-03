
package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Patch
import io.micronaut.http.annotation.Post
import io.micronaut.http.client.annotation.Client
import io.micronaut.runtime.server.EmbeddedServer
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong

/**
 * @author graemerocher
 * @since 1.0
 */
class JettyCompletableFutureCrudSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext context = ApplicationContext.run()

    @Shared
    EmbeddedServer embeddedServer = context.getBean(EmbeddedServer).start()

    void "test it is possible to implement CRUD operations with CompletableFuture"() {
        given:
        BookClient client = context.getBean(BookClient)

        when:
        Book book = client.get(99)
                .get()
        List<Book> books = client.list().get()

        then:
        book == null
        books.size() == 0

        when:
        book = client.save("The Stand").get()

        then:
        book != null
        book.title == "The Stand"
        book.id == 1

        when:
        book = client.get(book.id).get()

        then:
        book != null
        book.title == "The Stand"
        book.id == 1

        when:
        book = client.update(book.id, "The Shining").get()

        then:
        book != null
        book.title == "The Shining"
        book.id == 1

        when:
        book = client.delete(book.id).get()

        then:
        book != null

        when:
        book = client.get(book.id)
                .get()
        then:
        book == null
    }


    @Client('/future/books')
    static interface BookClient extends BookApi {
    }

    @Controller("/future/books")
    static class BookController implements BookApi {

        Map<Long, Book> books = new LinkedHashMap<>()
        AtomicLong currentId = new AtomicLong(0)

        @Override
        CompletionStage<Book> get(Long id) {
            Book book = books.get(id)
            return CompletableFuture.completedFuture(book)
        }

        @Override
        CompletableFuture<List<Book>> list() {
            return CompletableFuture.completedFuture(books.values().toList())
        }

        @Override
        CompletableFuture<Book> delete(Long id) {
            Book book = books.remove(id)
            return CompletableFuture.completedFuture(book)
        }

        @Override
        CompletableFuture<Book> save(String title) {
            Book book = new Book(title: title, id:currentId.incrementAndGet())
            books[book.id] = book
            return CompletableFuture.completedFuture(book)
        }

        @Override
        CompletableFuture<Book> update(Long id, String title) {
            Book book = books[id]
            if(book != null) {
                book.title = title
            }
            return CompletableFuture.completedFuture( book)
        }
    }

    static interface BookApi {

        @Get("/{id}")
        CompletionStage<Book> get(Long id)

        @Get
        CompletableFuture<List<Book>> list()

        @Delete("/{id}")
        CompletableFuture<Book> delete(Long id)

        @Post
        CompletableFuture<Book> save(String title)

        @Patch("/{id}")
        CompletableFuture<Book> update(Long id, String title)
    }


    static class Book {
        Long id
        String title
    }
}

