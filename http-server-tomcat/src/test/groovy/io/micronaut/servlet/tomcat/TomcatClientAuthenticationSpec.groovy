package io.micronaut.servlet.tomcat

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.core.io.socket.SocketUtils
import io.micronaut.http.HttpRequest
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.runtime.server.EmbeddedServer
import io.netty.handler.ssl.util.SelfSignedCertificate
import spock.lang.Shared
import spock.lang.Specification

import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.X509Certificate

/**
 * Verifies that the {@code micronaut.ssl} client authentication, protocol and cipher settings are enforced by the
 * TLS handshake itself, using plain JDK clients rather than a Micronaut client configured from the same settings.
 */
class TomcatClientAuthenticationSpec extends Specification {

    static final String SPEC_NAME = 'TomcatClientAuthenticationSpec'

    @Shared Path keyStorePath = Files.createTempFile("micronaut-test-key-store", "pkcs12")
    @Shared Path trustStorePath = Files.createTempFile("micronaut-test-trust-store", "jks")
    @Shared String keyAlgorithm

    void setupSpec() {
        def certificate = new SelfSignedCertificate()
        keyAlgorithm = certificate.key().algorithm

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

    void cleanupSpec() {
        Files.deleteIfExists(keyStorePath)
        Files.deleteIfExists(trustStorePath)
    }

    void "client-authentication WANT accepts a client without a certificate and exposes a presented one"() {
        given:
        EmbeddedServer server = start('micronaut.ssl.client-authentication': 'WANT')

        expect:
        get(trustOnlySslContext(), server.port, '/client-auth') == 'none'
        get(clientKeySslContext(), server.port, '/client-auth') == 'CN=localhost'

        cleanup:
        server?.close()
    }

    void "client-authentication NEED is enforced on a port exposed for management endpoints"() {
        given:
        int managementPort = SocketUtils.findAvailableTcpPort()
        EmbeddedServer server = start(
                'micronaut.ssl.client-authentication': 'NEED',
                'endpoints.all.enabled': true,
                'endpoints.all.port': managementPort,
        )

        when:
        get(trustOnlySslContext(), managementPort, '/health', 'TLSv1.2')

        then:
        thrown(SSLHandshakeException)

        when:
        def health = get(clientKeySslContext(), managementPort, '/health')

        then:
        health == '{"status":"UP"}'

        cleanup:
        server?.close()
    }

    void "micronaut.ssl protocols and ciphers restrict the handshake"() {
        given:
        String cipher = keyAlgorithm == 'RSA' ? 'TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256' : 'TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256'
        EmbeddedServer server = start(
                'micronaut.ssl.protocols': ['TLSv1.2'],
                'micronaut.ssl.ciphers': [cipher],
        )

        when:
        def session = handshake(server.port, null, null)

        then:
        session.protocol == 'TLSv1.2'
        session.cipherSuite == cipher

        when:
        handshake(server.port, ['TLSv1.3'] as String[], null)

        then:
        thrown(SSLException)

        when:
        handshake(server.port, ['TLSv1.2'] as String[], ['TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384', 'TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384'] as String[])

        then:
        thrown(SSLException)

        cleanup:
        server?.close()
    }

    private EmbeddedServer start(Map<String, Object> extra) {
        ApplicationContext.run(EmbeddedServer, [
                'spec.name'                              : SPEC_NAME,
                'micronaut.server.host'                  : 'localhost',
                'micronaut.ssl.enabled'                  : true,
                'micronaut.server.ssl.port'              : -1,
                'micronaut.server.ssl.build-self-signed' : false,
                'micronaut.ssl.key-store.path'           : 'file://' + keyStorePath.toString(),
                'micronaut.ssl.key-store.type'           : 'PKCS12',
                'micronaut.ssl.key-store.password'       : '',
                'micronaut.ssl.trust-store.path'         : 'file://' + trustStorePath.toString(),
                'micronaut.ssl.trust-store.type'         : 'JKS',
                'micronaut.ssl.trust-store.password'     : '123456',
        ] + extra as Map<String, Object>)
    }

    // a rejected client certificate surfaces as an SSLHandshakeException only with TLSv1.2: with TLSv1.3 the client
    // completes its side of the handshake and may send the request before reading the certificate_required alert,
    // so the rejection shows up as a connection reset instead
    private static String get(SSLContext sslContext, int port, String path, String... protocols) {
        def builder = HttpClient.newBuilder().sslContext(sslContext)
        if (protocols) {
            builder.sslParameters(new SSLParameters(null, protocols))
        }
        def client = builder.build()
        def response = client.send(java.net.http.HttpRequest.newBuilder(URI.create("https://localhost:${port}${path}")).build(),
                HttpResponse.BodyHandlers.ofString())
        assert response.statusCode() == 200
        response.body()
    }

    private javax.net.ssl.SSLSession handshake(int port, String[] protocols, String[] ciphers) {
        try (SSLSocket socket = (SSLSocket) trustOnlySslContext().socketFactory.createSocket('localhost', port)) {
            if (protocols != null) {
                socket.enabledProtocols = protocols
            }
            if (ciphers != null) {
                socket.enabledCipherSuites = ciphers
            }
            socket.startHandshake()
            return socket.session
        }
    }

    private SSLContext trustOnlySslContext() {
        sslContext(null)
    }

    private SSLContext clientKeySslContext() {
        KeyStore ks = KeyStore.getInstance("PKCS12")
        try (InputStream is = Files.newInputStream(keyStorePath)) {
            ks.load(is, "".toCharArray())
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "".toCharArray())
        sslContext(kmf)
    }

    private SSLContext sslContext(KeyManagerFactory kmf) {
        KeyStore ts = KeyStore.getInstance("JKS")
        try (InputStream is = Files.newInputStream(trustStorePath)) {
            ts.load(is, "123456".toCharArray())
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ts)
        SSLContext context = SSLContext.getInstance("TLS")
        context.init(kmf?.keyManagers, tmf.trustManagers, null)
        context
    }

    @Requires(property = 'spec.name', value = SPEC_NAME)
    @Controller
    static class TestController {

        @Get('/client-auth')
        String clientAuth(HttpRequest<?> request) {
            request.getCertificate()
                    .map { (it as X509Certificate).subjectX500Principal.name }
                    .orElse('none')
        }
    }
}
