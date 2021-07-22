
package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.Introspected
import io.micronaut.core.bind.BeanPropertyBinder
import io.micronaut.http.annotation.Body
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

import java.util.concurrent.atomic.AtomicLong

/**
 * Created by graemerocher on 19/01/2018.
 */
class JettyBlockingPojoCrudSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext context = ApplicationContext.run(['spec.name': 'JettyBlockingPojoCrudSpec'])

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = context.getBean(EmbeddedServer).start()

    void "test CRUD operations on generated client that returns blocking responses"() {
        given:
        BookClient client = context.getBean(BookClient)

        when:
        Book book = client.get(99)
        List<Book> books = client.list()

        then:
        book == null
        books.size() == 0

        when:
        book = client.save(new Book(title:"The Stand"))

        then:
        book != null
        book.title == "The Stand"
        book.id == 1

        when:
        book = client.get(book.id)

        then:
        book != null
        book.title == "The Stand"
        book.id == 1

        when:
        book = client.update(new Book(id:book.id, title:"The Shining"))

        then:
        book != null
        book.title == "The Shining"
        book.id == 1

        when:
        client.delete(book.id)

        then:
        client.get(book.id) == null
    }

    @Requires(property = 'spec.name', value = 'JettyBlockingPojoCrudSpec')
    @Client('/blocking/pojo/books')
    static interface BookClient extends BookApi {
    }

    @Requires(property = 'spec.name', value = 'JettyBlockingPojoCrudSpec')
    @Controller("/blocking/pojo/books")
    static class BookController implements BookApi {

        Map<Long, Book> books = new LinkedHashMap<>()
        AtomicLong currentId = new AtomicLong(0)

        BeanPropertyBinder binder

        BookController(BeanPropertyBinder binder) {
            this.binder = binder
        }

        @Override
        Book get(Long id) {
            return books.get(id)
        }

        @Override
        List<Book> list() {
            return books.values().toList()
        }

        @Override
        void delete(Long id) {
            books.remove(id)
        }

        @Override
        Book save(Book book) {
            book.id = currentId.incrementAndGet()
            books[book.id] = book
            return book
        }

        @Override
        Book update(Book book) {
            Book existing = books[book.id]
            if(book != null) {
                return binder.bind(existing, book)
            }
            return book
        }
    }

    static interface BookApi {

        @Get("/{id}")
        Book get(Long id)

        @Get
        List<Book> list()

        @Delete("/{id}")
        void delete(Long id)

        @Post
        Book save(@Body Book book)

        @Patch("/{id}")
        Book update(@Body Book book)
    }

    @Introspected
    static class Book {
        Long id
        String title
    }
}

