/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.servlet.engine.bind

import io.micronaut.context.BeanProvider
import io.micronaut.core.convert.ConversionContext
import io.micronaut.core.convert.ConversionService
import io.micronaut.core.type.Argument
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.body.MessageBodyHandlerRegistry
import io.micronaut.http.body.MessageBodyReader
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.http.multipart.CompletedFileUpload
import io.micronaut.http.multipart.CompletedPart
import io.micronaut.http.server.HttpServerConfiguration
import io.micronaut.servlet.http.ServletExchange
import io.micronaut.servlet.http.ServletHttpRequest
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.Part
import spock.lang.Specification

/**
 * A {@code @Part CompletedPart} or {@code CompletedFileUpload} argument wraps the part itself,
 * whatever the part's content type is, so it is resolved before the message body reader registered
 * for that content type.
 */
class ServletPartBinderSpec extends Specification {

    void "a text/plain part binds to #type.simpleName instead of being read by a message body reader"() {
        given:
        MessageBodyReader<?> reader = Mock(MessageBodyReader)
        MessageBodyHandlerRegistry registry = Stub(MessageBodyHandlerRegistry) {
            findReader(_, _) >> Optional.of(reader)
        }
        def binder = newBinder(registry)
        def context = ConversionContext.of(Argument.of(type, 'data'))

        when:
        def result = binder.bind(context, multipartExchange(textPart('hello')))

        then:
        result.value.present
        type.isInstance(result.value.get())
        result.value.get().name == 'data'
        new String(result.value.get().bytes) == 'hello'
        0 * reader.read(*_)

        where:
        type << [CompletedFileUpload, CompletedPart]
    }

    void "a part that cannot be read is a bad request"() {
        given:
        def binder = newBinder(Stub(MessageBodyHandlerRegistry))
        def context = ConversionContext.of(Argument.of(CompletedFileUpload, 'data'))

        when:
        binder.bind(context, multipartExchange(unreadablePart()))

        then:
        HttpStatusException e = thrown()
        e.status == HttpStatus.BAD_REQUEST
        e.message == 'Unable to read part [data]: broken pipe'
    }

    private ServletPartBinder<?> newBinder(MessageBodyHandlerRegistry registry) {
        new ServletPartBinder<>(
            ConversionService.SHARED,
            Stub(BeanProvider),
            registry,
            new HttpServerConfiguration()
        )
    }

    private Part textPart(String content) {
        Stub(Part) {
            getName() >> 'data'
            getSubmittedFileName() >> 'data.txt'
            getContentType() >> MediaType.TEXT_PLAIN
            getSize() >> content.bytes.length
            getInputStream() >> { new ByteArrayInputStream(content.bytes) }
        }
    }

    private Part unreadablePart() {
        Stub(Part) {
            getName() >> 'data'
            getSubmittedFileName() >> 'data.txt'
            getContentType() >> MediaType.TEXT_PLAIN
            getSize() >> 5L
            getInputStream() >> { throw new IOException('broken pipe') }
        }
    }

    private HttpRequest<?> multipartExchange(Part part) {
        HttpServletRequest nativeRequest = Stub(HttpServletRequest) {
            getPart('data') >> part
        }
        ServletHttpRequest<?, ?> servletRequest = Stub(ServletHttpRequest) {
            getNativeRequest() >> nativeRequest
        }
        Stub(additionalInterfaces: [ServletExchange], HttpRequest) {
            getContentType() >> Optional.of(MediaType.MULTIPART_FORM_DATA_TYPE)
            getRequest() >> servletRequest
        }
    }
}
