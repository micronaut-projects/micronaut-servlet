package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.core.util.StringUtils
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.HttpClient
import io.micronaut.runtime.server.EmbeddedServer
import io.netty.handler.ssl.util.SelfSignedCertificate
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.Certificate

class JettySslSpec extends Specification {

    void "test certificate extraction"() {
        given:
        Path keyStorePath = Files.createTempFile("micronaut-test-key-store", "pkcs12")
        Path trustStorePath = Files.createTempFile("micronaut-test-trust-store", "jks")

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

        and:
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer, [
                'spec.name'                                : 'JettySslSpec',
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

        ])
        HttpClient rxClient = server.getApplicationContext().createBean(HttpClient, URI.create("https://localhost:$server.port/"))

        when:
        def response = rxClient.toBlocking()
                .exchange('/ssl', String)

        then:
        response.code() == HttpStatus.OK.code
        response.body() == "true"

        cleanup:
        server.stop()
        Files.deleteIfExists(keyStorePath)
        Files.deleteIfExists(trustStorePath)
    }

    @Controller
    @Requires(property = 'spec.name', value = 'JettySslSpec')
    static class TestController {

        @Get('/ssl')
        String html(HttpRequest<?> request) {
            request.isSecure()
        }
    }
}

