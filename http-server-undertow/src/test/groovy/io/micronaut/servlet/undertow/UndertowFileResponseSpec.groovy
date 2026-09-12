package io.micronaut.servlet.undertow

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.server.types.files.StreamedFile
import io.micronaut.http.server.types.files.SystemFile
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/**
 * File responses: what the servlet engine puts on the wire for the file types the framework offers.
 */
@MicronautTest
@Property(name = 'spec.name', value = 'UndertowFileResponseSpec')
class UndertowFileResponseSpec extends Specification {

    static final String CONTENT = 'the quick brown fox jumps over the lazy dog'

    @Shared
    @AutoCleanup('deleteDir')
    static Path directory = Files.createTempDirectory('UndertowFileResponseSpec')

    @Shared
    static Path file = Files.writeString(directory.resolve('sample.txt'), CONTENT)

    @Inject
    @Client('/')
    HttpClient client

    void "a system file is sent with its length"() {
        when:
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET('/files/system'), String)

        then:
        response.status == HttpStatus.OK
        response.body() == CONTENT
        response.header(HttpHeaders.CONTENT_LENGTH) == String.valueOf(CONTENT.length())
    }

    void "a system file carries a last modified date and a cache control header"() {
        when:
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET('/files/system'), String)

        then:
        response.header(HttpHeaders.LAST_MODIFIED) != null
        response.header(HttpHeaders.CACHE_CONTROL) != null
    }

    void "a system file is not resent when the client already has it"() {
        given:
        HttpResponse<String> first = client.toBlocking().exchange(HttpRequest.GET('/files/system'), String)
        String lastModified = first.header(HttpHeaders.LAST_MODIFIED)

        when:
        HttpResponse<String> second = client.toBlocking().exchange(
            HttpRequest.GET('/files/system').header(HttpHeaders.IF_MODIFIED_SINCE, lastModified), String)

        then:
        second.status == HttpStatus.NOT_MODIFIED
        !second.body()
    }

    void "a system file advertises and honours byte ranges"() {
        when:
        HttpResponse<String> response = client.toBlocking().exchange(
            HttpRequest.GET('/files/system').header(HttpHeaders.RANGE, 'bytes=4-8'), String)

        then:
        response.status == HttpStatus.PARTIAL_CONTENT
        response.body() == CONTENT.substring(4, 9)
        response.header(HttpHeaders.CONTENT_RANGE) == "bytes 4-8/${CONTENT.length()}"
        response.header(HttpHeaders.ACCEPT_RANGES) == 'bytes'
    }

    void "an attached system file names the download"() {
        when:
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET('/files/attached'), String)

        then:
        response.status == HttpStatus.OK
        response.header(HttpHeaders.CONTENT_DISPOSITION)?.contains('sample.txt')
    }

    void "a streamed file is sent"() {
        when:
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET('/files/streamed'), String)

        then:
        response.status == HttpStatus.OK
        response.body() == CONTENT
    }

    @Requires(property = 'spec.name', value = 'UndertowFileResponseSpec')
    @Controller('/files')
    static class FileController {

        @Get('/system')
        SystemFile system() {
            new SystemFile(file.toFile(), MediaType.TEXT_PLAIN_TYPE)
        }

        @Get('/attached')
        SystemFile attached() {
            new SystemFile(file.toFile(), MediaType.TEXT_PLAIN_TYPE).attach('sample.txt')
        }

        @Get('/streamed')
        StreamedFile streamed() {
            new StreamedFile(Files.newInputStream(file), MediaType.TEXT_PLAIN_TYPE)
        }
    }
}
