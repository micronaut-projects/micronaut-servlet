
package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.context.env.Environment
import io.micronaut.context.exceptions.BeanInstantiationException
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import io.micronaut.web.router.resource.StaticResourceConfiguration
import jakarta.inject.Inject
import spock.lang.Issue
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static io.micronaut.http.HttpHeaders.CACHE_CONTROL
import static io.micronaut.http.HttpHeaders.CONTENT_LENGTH
import static io.micronaut.http.HttpHeaders.CONTENT_TYPE

@MicronautTest
class JettyStaticResourceResolutionSpec extends Specification implements TestPropertyProvider {

    private static Path tempDir
    private static File tempFile

    static {
        tempDir = Files.createTempDirectory(Paths.get(System.getProperty("user.dir")+"/build"),"tmp")
        tempFile = Files.createTempFile(tempDir,"staticResourceResolutionSpec", ".html").toFile()
        tempFile.write("<html><head></head><body>HTML Page from static file</body></html>")
        tempDir.toFile().deleteOnExit()
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
    HttpClient rxClient

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
        def response = rxClient.toBlocking().exchange(
                HttpRequest.GET('/public/'+tempFile.getName()), String
        )

        then:
        response.status == HttpStatus.OK
        response.header(CONTENT_TYPE) == "text/html"
        Integer.parseInt(response.header(CONTENT_LENGTH)) > 0
        response.headers.contains(CACHE_CONTROL)
        response.header(CACHE_CONTROL) == "private,max-age=60"
        response.body() == "<html><head></head><body>HTML Page from static file</body></html>"
    }

    void "test resources from the classpath are returned"() {
        when:
        HttpResponse<String> response = rxClient.toBlocking().exchange(
                HttpRequest.GET('/public/index.html'), String
        )

        File file = Paths.get(JettyStaticResourceResolutionSpec.classLoader.getResource("public/index.html").toURI()).toFile()

        then:
        file.exists()
        response.status == HttpStatus.OK
        response.header(CONTENT_TYPE) == "text/html"
        Integer.parseInt(response.header(CONTENT_LENGTH)) > 0
        response.headers.contains(CACHE_CONTROL)
        response.header(CACHE_CONTROL) == "private,max-age=60"

        response.body() == "<html><head></head><body>HTML Page from resources</body></html>"
    }

    void "test index.html will be resolved"() {
        when:
        HttpResponse<String> response = rxClient.toBlocking().exchange(
                HttpRequest.GET('/public'), String
        )

        File file = Paths.get(JettyStaticResourceResolutionSpec.classLoader.getResource("public/index.html").toURI()).toFile()

        then:
        file.exists()
        response.status == HttpStatus.OK
        response.header(CONTENT_TYPE) == "text/html"
        Integer.parseInt(response.header(CONTENT_LENGTH)) > 0
        response.headers.contains(CACHE_CONTROL)
        response.header(CACHE_CONTROL) == "private,max-age=60"

        response.body() == "<html><head></head><body>HTML Page from resources</body></html>"
    }

    void "test resources with configured mapping"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'micronaut.router.static-resources.default.paths': ['classpath:public', 'file:' + tempFile.parent],
                'micronaut.router.static-resources.default.mapping': '/static/**'])
        HttpClient rxClient = embeddedServer.applicationContext.createBean(HttpClient, embeddedServer.getURL())


        when:
        HttpResponse<String> response = rxClient.toBlocking().exchange(
                HttpRequest.GET("/static/index.html"), String
        )
        File file = Paths.get(JettyStaticResourceResolutionSpec.classLoader.getResource("public/index.html").toURI()).toFile()

        then:
        file.exists()
        response.code() == HttpStatus.OK.code
        response.header(CONTENT_TYPE) == "text/html"
        Integer.parseInt(response.header(CONTENT_LENGTH)) > 0
        response.headers.contains(CACHE_CONTROL)

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
        HttpClient rxClient = embeddedServer.applicationContext.createBean(HttpClient, embeddedServer.getURL())

        expect:
        embeddedServer.applicationContext.getBeansOfType(StaticResourceConfiguration).size() == 2

        when:
        HttpResponse<String> response = rxClient.toBlocking().exchange(
                HttpRequest.GET("/static/index.html"), String
        )
        File file = Paths.get(JettyStaticResourceResolutionSpec.classLoader.getResource("public/index.html").toURI()).toFile()

        then:
        file.exists()
        response.code() == HttpStatus.OK.code
        response.header(CONTENT_TYPE) == "text/html"
        Integer.parseInt(response.header(CONTENT_LENGTH)) > 0
        response.headers.contains(CACHE_CONTROL)

        response.body() == "<html><head></head><body>HTML Page from resources</body></html>"

        when:
        response = rxClient.toBlocking().exchange(
                HttpRequest.GET('/file/'+tempFile.getName()), String
        )

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
        HttpClient rxClient = embeddedServer.applicationContext.createBean(HttpClient, embeddedServer.getURL())

        when:
        def response = rxClient.toBlocking().exchange(
                HttpRequest.GET("/static"), String
        )
        File file = Paths.get(JettyStaticResourceResolutionSpec.classLoader.getResource("public/index.html").toURI()).toFile()

        then:
        file.exists()
        response.code() == HttpStatus.OK.code
        response.header(CONTENT_TYPE) == "text/html"
        Integer.parseInt(response.header(CONTENT_LENGTH)) > 0
        response.headers.contains(CACHE_CONTROL)
        response.body() == "<html><head></head><body>HTML Page from resources</body></html>"

        cleanup:
        embeddedServer.stop()
        embeddedServer.close()
    }

    void "test resources with configured mapping automatically resolves index.html in path"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'micronaut.router.static-resources.default.paths': ['classpath:public'],
                'micronaut.router.static-resources.default.mapping': '/static/**',
                'micronaut.router.static-resources.default.cache-control': '', // clear the cache control header
        ])
        HttpClient rxClient = embeddedServer.applicationContext.createBean(HttpClient, embeddedServer.getURL())


        when:
        def response = rxClient.toBlocking().exchange(
                HttpRequest.GET("/static/foo"), String
        )
        File file = Paths.get(JettyStaticResourceResolutionSpec.classLoader.getResource("public/foo/index.html").toURI()).toFile()

        then:
        file.exists()
        response.code() == HttpStatus.OK.code
        response.header(CONTENT_TYPE) == "text/html"
        Integer.parseInt(response.header(CONTENT_LENGTH)) > 0
        response.body() == "<html><head></head><body>HTML Page from resources/foo</body></html>"

        and: 'the cache control header is not set'
        !response.headers.contains(CACHE_CONTROL)

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

    @Issue("https://github.com/micronaut-projects/micronaut-servlet/issues/251")
    void "test resources with mapping names that are prefixes of one another can resolve index.html and a resource"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'micronaut.router.static-resources.nest.paths': ['classpath:nest-test/nested'],
                'micronaut.router.static-resources.nest.mapping': '/nest/**', // This mapping
                'micronaut.router.static-resources.nest-test.paths': ['classpath:nest-test'],
                'micronaut.router.static-resources.nest-test.mapping': '/nest-test/**', // is a prefix of this mapping (same with swagger and swagger-ui)
        ])
        def client = embeddedServer.applicationContext.createBean(HttpClient, embeddedServer.getURL()).toBlocking()

        when:
        def nestResponse = client.exchange(HttpRequest.GET("/nest"), String)
        def nestText = this.class.classLoader.getResource("nest-test/nested/index.html").text

        def nestTestResponse = client.exchange(HttpRequest.GET("/nest-test/something.txt"), String)
        def nestTestText = this.class.classLoader.getResource("nest-test/something.txt").text

        then:
        with(nestResponse) {
            code() == HttpStatus.OK.code
            header(CONTENT_TYPE) == "text/html"
            Integer.parseInt(header(CONTENT_LENGTH)) > 0
            body() == nestText
        }

        with(nestTestResponse) {
            code() == HttpStatus.OK.code
            Integer.parseInt(header(CONTENT_LENGTH)) > 0
            body() == nestTestText
        }

        cleanup:
        embeddedServer.stop()
        embeddedServer.close()
    }

    @Issue("https://github.com/micronaut-projects/micronaut-servlet/issues/251")
    void "multiple index.html files causes issues with the static resource handling"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, [
                'micronaut.router.static-resources.nest.paths': ['classpath:nest-test/nested'],
                'micronaut.router.static-resources.nest.mapping': '/nest/**',
                'micronaut.router.static-resources.public.paths': ['classpath:public'],
                'micronaut.router.static-resources.public.mapping': '/public/**',
        ])
        def client = embeddedServer.applicationContext.createBean(HttpClient, embeddedServer.getURL()).toBlocking()

        when:
        def nestResponse = client.exchange(HttpRequest.GET("/nest"), String)
        def nestText = this.class.classLoader.getResource("nest-test/nested/index.html").text

        def publicResponse = client.exchange(HttpRequest.GET("/public/index.html"), String)
        def publicText = this.class.classLoader.getResource("public/index.html").text

        then:
        with(nestResponse) {
            code() == HttpStatus.OK.code
            header(CONTENT_TYPE) == "text/html"
            Integer.parseInt(header(CONTENT_LENGTH)) > 0
            body() == nestText
        }

        with(publicResponse) {
            code() == HttpStatus.OK.code
            header(CONTENT_TYPE) == "text/html"
            Integer.parseInt(header(CONTENT_LENGTH)) > 0
            body() == publicText
        }

        cleanup:
        embeddedServer.stop()
        embeddedServer.close()
    }
}
