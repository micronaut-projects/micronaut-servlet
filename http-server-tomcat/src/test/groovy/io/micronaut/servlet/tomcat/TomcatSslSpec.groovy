/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.servlet.tomcat

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.client.RxHttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import spock.lang.Specification

import javax.inject.Inject

@MicronautTest
class TomcatSslSpec extends Specification implements TestPropertyProvider {

    @Inject
    @Client("/")
    RxHttpClient rxClient

    void "test certificate extraction"() {
        when:
        def response = rxClient
                .exchange('/ssl', String)
                .blockingFirst()
        then:
        response.code() == HttpStatus.OK.code
        response.body() == "true"
    }


    @Override
    Map<String, String> getProperties() {
        return [
                'micronaut.ssl.enabled': true,
                // Cannot be true!
                'micronaut.ssl.buildSelfSigned': false,
                'micronaut.ssl.clientAuthentication': "need",
                'micronaut.ssl.key-store.path': 'classpath:KeyStore.p12',
                'micronaut.ssl.key-store.type': 'PKCS12',
                'micronaut.ssl.key-store.password': '',
                'micronaut.ssl.trust-store.path': 'classpath:TrustStore.jks',
                'micronaut.ssl.trust-store.type': 'JKS',
                'micronaut.ssl.trust-store.password': '123456',
        ]
    }

    @Controller
    static class TestController {

        @Get('/ssl')
        String html(HttpRequest<?> request) {
            return request.isSecure()
        }
    }
}

