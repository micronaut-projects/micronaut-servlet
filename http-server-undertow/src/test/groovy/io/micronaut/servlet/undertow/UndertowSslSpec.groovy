
package io.micronaut.servlet.undertow

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.util.StringUtils
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import spock.lang.Specification

import jakarta.inject.Inject

@Property(name = 'spec.name', value = 'UndertowSslSpec')
@MicronautTest
class UndertowSslSpec extends Specification implements TestPropertyProvider {

    @Inject
    @Client("/")
    HttpClient rxClient

    void "test certificate extraction"() {
        when:
        def response = rxClient.toBlocking()
                .exchange('/ssl', String)
                
        then:
        response.code() == HttpStatus.OK.code
        response.body() == "true"
    }

    @Override
    Map<String, String> getProperties() {
        [
                'micronaut.ssl.enabled': StringUtils.TRUE,
                // Cannot be true!
                'micronaut.ssl.buildSelfSigned': StringUtils.FALSE,
                'micronaut.ssl.clientAuthentication': "need",
                'micronaut.ssl.key-store.path': 'classpath:KeyStore.pkcs12',
                'micronaut.ssl.key-store.type': 'PKCS12',
                'micronaut.ssl.key-store.password': '',
                'micronaut.ssl.trust-store.path': 'classpath:TrustStore.jks',
                'micronaut.ssl.trust-store.type': 'JKS',
                'micronaut.ssl.trust-store.password': '123456',
        ]
    }

    @Requires(property = 'spec.name', value = 'UndertowSslSpec')
    @Controller
    static class TestController {

        @Get('/ssl')
        String html(HttpRequest<?> request) {
            return request.isSecure()
        }
    }
}
