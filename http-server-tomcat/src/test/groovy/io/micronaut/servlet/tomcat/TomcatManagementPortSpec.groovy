package io.micronaut.servlet.tomcat

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.core.io.socket.SocketUtils
import io.micronaut.core.util.StringUtils
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.runtime.server.EmbeddedServer
import io.netty.handler.ssl.util.SelfSignedCertificate
import spock.lang.Issue
import spock.lang.PendingFeature
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.Certificate

@Issue("https://github.com/micronaut-projects/micronaut-servlet/issues/616")
class TomcatManagementPortSpec extends Specification {

    static final Path keyStorePath = Files.createTempFile("micronaut-test-key-store", "pkcs12")
    static final Path trustStorePath = Files.createTempFile("micronaut-test-trust-store", "jks")

    def setupSpec() {
        def certificate = new SelfSignedCertificate()

        KeyStore ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("key", certificate.key(), "".toCharArray(), new Certificate[]{certificate.cert()})
        try (OutputStream os = Files.newOutputStream(keyStorePath)) {
            ks.store(os, "".toCharArray())
        }

        KeyStore ts = KeyStore.getInstance("JKS")
        ts.load(null, null)
        ts.setCertificateEntry("cert", certificate.cert())
        try (OutputStream os = Files.newOutputStream(trustStorePath)) {
            ts.store(os, "123456".toCharArray())
        }
    }

    def sslConfig() {
        [
                "micronaut.server.jetty.ssl.sni-host-check": StringUtils.FALSE,
                'micronaut.ssl.enabled'                    : StringUtils.TRUE,
                // Cannot be true!
                'micronaut.server.ssl.build-self-signed'   : false,
                'micronaut.ssl.clientAuthentication'       : "need",
                'micronaut.ssl.key-store.path'             : "file://${keyStorePath.toString()}",
                'micronaut.ssl.key-store.type'             : 'PKCS12',
                'micronaut.ssl.key-store.password'         : '',
                'micronaut.ssl.trust-store.path'           : "file://${trustStorePath.toString()}",
                'micronaut.ssl.trust-store.type'           : 'JKS',
                'micronaut.ssl.trust-store.password'       : '123456',
        ]
    }

    def 'management port can be configured different to main port'() {
        given:
        def port = SocketUtils.findAvailableTcpPort()
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer, [
                'spec.name'            : 'TomcatManagementPortSpec',
                'endpoints.all.enabled': true,
                'endpoints.all.port'   : port,
        ])
        BlockingHttpClient mainClient = server.getApplicationContext().createBean(HttpClient, URI.create("http://localhost:$server.port/")).toBlocking()
        BlockingHttpClient managementClient = server.getApplicationContext().createBean(HttpClient, URI.create("http://localhost:$port/")).toBlocking()

        when:
        def mainResponse = mainClient.exchange('/management-port', String)
        def healthResponse = managementClient.exchange('/health', String)

        then:
        mainResponse.body() == 'Hello world'
        healthResponse.body() == '{"status":"UP"}'

        when:
        mainClient.exchange('/health', String)

        then:
        def e = thrown(HttpClientResponseException)
        e.response.status() == HttpStatus.NOT_FOUND

        cleanup:
        mainClient.close()
        managementClient.close()
        server.stop()
    }

    def 'management port can be configured different to main port and uses ssl if also configured'() {
        given:
        def port = SocketUtils.findAvailableTcpPort()
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer, [
                'spec.name'            : 'TomcatManagementPortSpec',
                'endpoints.all.enabled': true,
                'endpoints.all.port'   : port,
        ] + sslConfig())
        BlockingHttpClient mainClient = server.getApplicationContext().createBean(HttpClient, URI.create("https://localhost:$server.port/")).toBlocking()
        BlockingHttpClient managementClient = server.getApplicationContext().createBean(HttpClient, URI.create("https://localhost:$port/")).toBlocking()

        when:
        def mainResponse = mainClient.exchange('/management-port', String)
        def healthResponse = managementClient.exchange('/health', String)

        then:
        mainResponse.body() == 'Hello world'
        healthResponse.body() == '{"status":"UP"}'

        when:
        mainClient.exchange('/health', String)

        then:
        def e = thrown(HttpClientResponseException)
        e.response.status() == HttpStatus.NOT_FOUND

        cleanup:
        mainClient.close()
        managementClient.close()
        server.stop()
    }

    @Controller("/management-port")
    @Requires(property = "spec.name", value = "TomcatManagementPortSpec")
    static class TestController {

        @Get
        String get() {
            "Hello world"
        }
    }
}
