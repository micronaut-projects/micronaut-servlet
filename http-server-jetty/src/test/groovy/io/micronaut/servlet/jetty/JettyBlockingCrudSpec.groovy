
package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.http.client.annotation.Client
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import spock.lang.Specification

import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong

@MicronautTest
class JettyBlockingCrudSpec extends Specification {

    @Inject
    ApplicationContext context

    @Inject
    EmbeddedServer embeddedServer

    void "test configured client"() {
        given:
        ApplicationContext anotherContext = ApplicationContext.run(
                'book.service.uri':"${embeddedServer.URL}/blocking"
        )
        ConfiguredBookClient bookClient = anotherContext.getBean(ConfiguredBookClient)

        expect:
        bookClient.list().size() == 0

        cleanup:
        anotherContext.close()
    }

    void "test CRUD operations on generated client that returns blocking responses"() {
        given:
        BookClient client = context.getBean(BookClient)

        when:
        Book book = client.get(99)
        Optional<Book> opt = client.getOptional(99)
        List<Book> books = client.list()

        then:
        book == null
        !opt.isPresent()
        books.size() == 0

        when:
        book = client.save("The Stand")

        then:
        book != null
        book.title == "The Stand"
        book.id == 1

        when:
        book = client.get(book.id)
        opt = client.getOptional(book.id)

        then:
        book != null
        book.title == "The Stand"
        book.id == 1
        opt.isPresent()
        opt.get().title == book.title

        when:'the full response is resolved'
        HttpResponse<Book> bookAndResponse = client.getResponse(book.id)

        then:"The response is valid"
        bookAndResponse.status() == HttpStatus.OK
        bookAndResponse.body().title == "The Stand"

        when:'the full response returns 404'
        bookAndResponse = client.getResponse(-1)

        then:
        noExceptionThrown()
        bookAndResponse.status() == HttpStatus.NOT_FOUND

        when:
        book = client.update(book.id, "The Shining")
        books = client.list()

        then:
        book != null
        book.title == "The Shining"
        book.id == 1
        books.size() == 1
        books.first() instanceof Book

        when:
        client.delete(book.id)

        then:
        client.get(book.id) == null
    }

    void "test DELETE operation with null values"() {
        given:
        BookClient client = context.getBean(BookClient)

        when:
        client.delete(null)

        then:
        thrown(IllegalArgumentException)
    }

    void "test POST operation with null values"() {
        given:
        BookClient client = context.getBean(BookClient)

        when:
        Book book = client.save(null)

        then:
        thrown(IllegalArgumentException)
    }

    void "test PUT operation with null values"() {
        given:
        BookClient client = context.getBean(BookClient)

        when:
        Book book = client.update(5, null)

        then:
        thrown(IllegalArgumentException)
    }

    void "test GET operation with null values"() {
        given:
        BookClient client = context.getBean(BookClient)

        when:
        Book book = client.get(null)

        then:
        thrown(IllegalArgumentException)
    }

    void "test a declarative client void method and 404 response"() {
        given:
        VoidNotFoundClient client = context.getBean(VoidNotFoundClient)

        when:
        client.call()

        then:
        noExceptionThrown()
    }

    @Client('/blocking/books')
    static interface BookClient extends BookApi {
    }

    @Client('${book.service.uri}/books')
    static interface ConfiguredBookClient extends BookApi {
    }

    @Controller("/blocking/books")
    static class BookController implements BookApi {

        Map<Long, Book> books = new LinkedHashMap<>()
        AtomicLong currentId = new AtomicLong(0)

        @Override
        Book get(Long id) {
            return books.get(id)
        }

        @Override
        Optional<Book> getOptional(Long id) {
            return Optional.ofNullable(get(id))
        }

        @Override
        HttpResponse<Book> getResponse(Long id) {
            def book = books.get(id)
            if(book) {
                return HttpResponse.ok(book)
            }
            return HttpResponse.notFound()
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
        Book save(String title) {
            Book book = new Book(title: title, id:currentId.incrementAndGet())
            books[book.id] = book
            return book
        }

        @Override
        Book update(Long id, String title) {
            Book book = books[id]
            if(book != null) {
                book.title = title
            }
            return book
        }
    }

    static interface BookApi {

        @Get("/{id}")
        Book get(Long id)

        @Get("/optional/{id}")
        Optional<Book> getOptional(Long id)

        @Get("/res/{id}")
        HttpResponse<Book> getResponse(Long id)

        @Get
        List<Book> list()

        @Delete("/{id}")
        void delete(Long id)

        @Post
        Book save(String title)

        @Patch("/{id}")
        Book update(Long id, String title)
    }


    static class Book {
        Long id
        String title
    }

    @Client("/void/404")
    static interface VoidNotFoundClient {

        @Get
        void call()
    }

}
