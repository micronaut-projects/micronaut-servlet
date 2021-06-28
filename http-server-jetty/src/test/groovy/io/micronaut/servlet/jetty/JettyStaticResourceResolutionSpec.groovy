
package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.context.env.Environment
import io.micronaut.context.exceptions.BeanInstantiationException
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.RxHttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import io.micronaut.web.router.resource.StaticResourceConfiguration
import spock.lang.Specification

import jakarta.inject.Inject
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

import static io.micronaut.http.HttpHeaders.*

@MicronautTest
class JettyStaticResourceResolutionSpec extends Specification implements TestPropertyProvider {

    private static File tempFile

    static {
        tempFile = File.createTempFile("staticResourceResolutionSpec", ".html")
        tempFile.write("<html><head></head><body>HTML Page from static file</body></html>")
        tempFile
    }

    @Override
    Map<String, Object> getProperties() {
        [
                'micronaut.router.static-resources.default.paths': ['classpath:public', 'file:' + tempFile.parent],
                'micronaut.router.static-resources.default.mapping':'/public',
                'micronaut.server.jetty.init-parameters.cacheControl':'max-age=3600,public'
        ]
    }

    void cleanupSpec() {
        tempFile.delete()
    }

    @Inject
    @Client("/")
    RxHttpClient rxClient

    // tests that normal requests work when static resources enabled
    void "test URI parameters"() {
        given:
        HttpRequest request = HttpRequest.GET("/parameters/uri/Foo")
        HttpResponse<String> response = rxClient.toBlocking().exchange(request, String)

        expect:
        response.status() == HttpStatus.OK
        response.contentType.get() == MediaType.TEXT_PLAIN_TYPE
        response.body() == 'Hello Foo'
    }

    void "test resources from the file system are returned"() {
        when:
        def response = rxClient.exchange(
                HttpRequest.GET('/public/'+tempFile.getName()), String
        ).blockingFirst()

        then:
        response.status == HttpStatus.OK
        response.header(CONTENT_TYPE) == "text/html"
        Integer.parseInt(response.header(CONTENT_LENGTH)) > 0
        response.headers.contains(CACHE_CONTROL)
        response.header(CACHE_CONTROL) == "max-age=3600,public"
        response.body() == "<html><head></head><body>HTML Page from static file</body></html>"
    }

    void "test resources from the classpath are returned"() {
        when:
        HttpResponse<String> response = rxClient.exchange(
                HttpRequest.GET('/public/index.html'), String
        ).blockingFirst()

        File file = Paths.get(JettyStaticResourceResolutionSpec.classLoader.getResource("public/index.html").toURI()).toFile()

        then:
        file.exists()
        response.status == HttpStatus.OK
        response.header(CONTENT_TYPE) == "text/html"
        Integer.parseInt(response.header(CONTENT_LENGTH)) > 0
        response.headers.contains(CACHE_CONTROL)
        response.header(CACHE_CONTROL) == "max-age=3600,public"

        response.body() == "<html><head></head><body>HTML Page from resources</body></html>"
    }

    void "test index.html will be resolved"() {
        when:
        HttpResponse<String> response = rxClient.exchange(
                HttpRequest.GET('/public'), String
        ).blockingFirst()

        File file = Paths.get(JettyStaticResourceResolutionSpec.classLoader.getResource("public/index.html").toURI()).toFile()

        then:
        file.exists()
        response.status == HttpStatus.OK
        response.header(CONTENT_TYPE) == "text/html"
        Integer.parseInt(response.header(CONTENT_LENGTH)) > 0
        response.headers.contains(CACHE_CONTROL)
        response.header(CACHE_CONTROL) == "max-age=3600,public"

        response.body() == "<html><head></head><body>HTML Page from resources</body></html>"
    }

    void "test resources with configured mapping"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'micronaut.router.static-resources.default.paths': ['classpath:public', 'file:' + tempFile.parent],
                'micronaut.router.static-resources.default.mapping': '/static/**'])
        RxHttpClient rxClient = embeddedServer.applicationContext.createBean(RxHttpClient, embeddedServer.getURL())


        when:
        HttpResponse<String> response = rxClient.exchange(
                HttpRequest.GET("/static/index.html"), String
        ).blockingFirst()
        File file = Paths.get(JettyStaticResourceResolutionSpec.classLoader.getResource("public/index.html").toURI()).toFile()

        then:
        file.exists()
        response.code() == HttpStatus.OK.code
        response.header(CONTENT_TYPE) == "text/html"
        Integer.parseInt(response.header(CONTENT_LENGTH)) > 0
        !response.headers.contains(CACHE_CONTROL)

        response.body() == "<html><head></head><body>HTML Page from resources</body></html>"

        cleanup:
        rxClient.close()
        embeddedServer.stop()
    }

    void "test resources with multiple configured mappings and one is disabled"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'micronaut.router.static-resources.cp.paths': ['classpath:public', 'file:' + tempFile.parent],
                'micronaut.router.static-resources.cp.mapping': '/static/**',
                'micronaut.router.static-resources.file.paths': ['file:' + tempFile.parent],
                'micronaut.router.static-resources.file.enabled': false,
                'micronaut.router.static-resources.file.mapping': '/file/**'], Environment.TEST)
        RxHttpClient rxClient = embeddedServer.applicationContext.createBean(RxHttpClient, embeddedServer.getURL())

        expect:
        embeddedServer.applicationContext.getBeansOfType(StaticResourceConfiguration).size() == 2

        when:
        HttpResponse<String> response = rxClient.exchange(
                HttpRequest.GET("/static/index.html"), String
        ).blockingFirst()
        File file = Paths.get(JettyStaticResourceResolutionSpec.classLoader.getResource("public/index.html").toURI()).toFile()

        then:
        file.exists()
        response.code() == HttpStatus.OK.code
        response.header(CONTENT_TYPE) == "text/html"
        Integer.parseInt(response.header(CONTENT_LENGTH)) > 0
        !response.headers.contains(CACHE_CONTROL)

        response.body() == "<html><head></head><body>HTML Page from resources</body></html>"

        when:
        response = rxClient.exchange(
                HttpRequest.GET('/file/'+tempFile.getName()), String
        ).blockingFirst()

        then:
        thrown(HttpClientResponseException)

        cleanup:
        embeddedServer.stop()
    }

    void "test resources with configured mapping automatically resolves index.html"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'micronaut.router.static-resources.default.paths': ['classpath:public', 'file:' + tempFile.parent],
                'micronaut.router.static-resources.default.mapping': '/static/**'])
        RxHttpClient rxClient = embeddedServer.applicationContext.createBean(RxHttpClient, embeddedServer.getURL())

        when:
        def response = rxClient.exchange(
                HttpRequest.GET("/static"), String
        ).blockingFirst()
        File file = Paths.get(JettyStaticResourceResolutionSpec.classLoader.getResource("public/index.html").toURI()).toFile()

        then:
        file.exists()
        response.code() == HttpStatus.OK.code
        response.header(CONTENT_TYPE) == "text/html"
        Integer.parseInt(response.header(CONTENT_LENGTH)) > 0
        !response.headers.contains(CACHE_CONTROL)
        response.body() == "<html><head></head><body>HTML Page from resources</body></html>"

        cleanup:
        embeddedServer.stop()
        embeddedServer.close()
    }

    void "test resources with configured mapping automatically resolves index.html in path"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'micronaut.router.static-resources.default.paths': ['classpath:public'],
                'micronaut.router.static-resources.default.mapping': '/static/**'])
        RxHttpClient rxClient = embeddedServer.applicationContext.createBean(RxHttpClient, embeddedServer.getURL())


        when:
        def response = rxClient.exchange(
                HttpRequest.GET("/static/foo"), String
        ).blockingFirst()
        File file = Paths.get(JettyStaticResourceResolutionSpec.classLoader.getResource("public/foo/index.html").toURI()).toFile()

        then:
        file.exists()
        response.code() == HttpStatus.OK.code
        response.header(CONTENT_TYPE) == "text/html"
        Integer.parseInt(response.header(CONTENT_LENGTH)) > 0
        !response.headers.contains(CACHE_CONTROL)
        response.body() == "<html><head></head><body>HTML Page from resources/foo</body></html>"

        cleanup:
        embeddedServer.stop()
        embeddedServer.close()
    }

    void "test its not possible to configure a path with 'classpath:'"() {
        when:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'micronaut.router.static-resources.default.paths': ['classpath:'],
                'micronaut.router.static-resources.default.mapping': '/static/**'])

        then:
        BeanInstantiationException e = thrown()
        e.message.contains("A path value of [classpath:] will allow access to class files!")

        cleanup:
        embeddedServer?.stop()
        embeddedServer?.close()
    }
}
