package io.micronaut.servlet.jetty

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.context.env.PropertySource
import io.micronaut.context.env.SystemPropertiesPropertySource
import io.micronaut.context.event.ApplicationEventPublisher
import io.micronaut.core.type.Argument
import io.micronaut.core.util.StringUtils
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.exceptions.HttpClientException
import io.micronaut.runtime.context.scope.refresh.RefreshEvent
import io.micronaut.runtime.server.EmbeddedServer
import io.netty.handler.ssl.util.SelfSignedCertificate
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit

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

    void "test ssl context reload"() {
        given:
        Path keyStorePath1 = Files.createTempFile("micronaut-test-key-store-1-", "pkcs12")
        Path keyStorePath2 = Files.createTempFile("micronaut-test-key-store-2-", "pkcs12")
        Path trustStorePath = Files.createTempFile("micronaut-test-trust-store", "jks")

        Date notBefore = Date.from(Instant.now().minus(2, ChronoUnit.DAYS))
        Date notAfter1 = Date.from(Instant.now().minus(1, ChronoUnit.DAYS))
        Date notAfter2 = Date.from(Instant.now().plus(1, ChronoUnit.DAYS))

        def expiredCertificate = new SelfSignedCertificate(notBefore, notAfter1)
        def certificate = new SelfSignedCertificate(notBefore, notAfter2)

        KeyStore ks1 = KeyStore.getInstance("PKCS12")
        ks1.load(null, null)
        ks1.setKeyEntry("key", expiredCertificate.key(), "".toCharArray(), new Certificate[]{expiredCertificate.cert()})
        try (OutputStream os = Files.newOutputStream(keyStorePath1)) {
            ks1.store(os, "".toCharArray())
        }

        KeyStore ks2 = KeyStore.getInstance("PKCS12")
        ks2.load(null, null)
        ks2.setKeyEntry("key", certificate.key(), "".toCharArray(), new Certificate[]{certificate.cert()})
        try (OutputStream os = Files.newOutputStream(keyStorePath2)) {
            ks2.store(os, "".toCharArray())
        }

        KeyStore ts = KeyStore.getInstance("JKS")
        ts.load(null, null)
        ts.setCertificateEntry("cert", certificate.cert())
        try (OutputStream os = Files.newOutputStream(trustStorePath)) {
            ts.store(os, "123456".toCharArray())
        }

        and:
        PropertySource propertySource1 = PropertySource.of(PropertySource.CONTEXT, [
                'spec.name'                                : 'JettySslSpec',
                "micronaut.server.jetty.ssl.sni-host-check": StringUtils.FALSE,
                'micronaut.ssl.enabled'                    : StringUtils.TRUE,
                'micronaut.server.ssl.build-self-signed'   : false,
                'micronaut.ssl.clientAuthentication'       : "need",
                'micronaut.ssl.key-store.path'             : "file://${keyStorePath1.toString()}",
                'micronaut.ssl.key-store.type'             : 'PKCS12',
                'micronaut.ssl.key-store.password'         : '',
                'micronaut.ssl.trust-store.path'           : "file://${trustStorePath.toString()}",
                'micronaut.ssl.trust-store.type'           : 'JKS',
                'micronaut.ssl.trust-store.password'       : '123456'], SystemPropertiesPropertySource.POSITION + 100)
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer, propertySource1)
        ApplicationContext applicationContext = server.getApplicationContext()
        HttpClient rxClient = applicationContext.createBean(HttpClient, URI.create("https://localhost:$server.port/"))

        when:
        rxClient.toBlocking().exchange('/ssl', String)

        then:
        HttpClientException exception = thrown()
        exception.message.contains("validity check failed")

        when:
        PropertySource propertySource2 = PropertySource.of(PropertySource.CONTEXT + "-updated", [
                'micronaut.ssl.key-store.path': "file://${keyStorePath2.toString()}"], propertySource1.getOrder() - 50)
        applicationContext.getEnvironment().addPropertySource(propertySource2)
        ApplicationEventPublisher eventPublisher = applicationContext.getBean(Argument.of(ApplicationEventPublisher.class, RefreshEvent.class))
        eventPublisher.publishEvent(new RefreshEvent(["micronaut.ssl.key-store.path": null]))
        def response = rxClient.toBlocking().exchange('/ssl', String)

        then:
        response.code() == HttpStatus.OK.code
        response.body() == "true"

        cleanup:
        server.stop()
        Files.deleteIfExists(keyStorePath1)
        Files.deleteIfExists(keyStorePath2)
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

