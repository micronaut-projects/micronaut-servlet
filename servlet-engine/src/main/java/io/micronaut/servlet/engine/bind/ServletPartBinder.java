/*
 * Copyright 2017-2020 original authors
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
package io.micronaut.servlet.engine.bind;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.io.IOUtils;
import io.micronaut.core.io.Readable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import io.micronaut.http.codec.MediaTypeCodec;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.servlet.engine.ServletCompletedFileUpload;
import io.micronaut.servlet.http.ServletExchange;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * A binder capable of binding servlet multipart requests.
 * @param <T> The argument type
 *
 * @author graemerocher
 * @since 1.0.0
 */
public class ServletPartBinder<T> implements AnnotatedRequestArgumentBinder<Part, T> {

    private final MediaTypeCodecRegistry codecRegistry;

    /**
     * Default constructor.
     * @param codecRegistry The codec registry.
     */
    ServletPartBinder(MediaTypeCodecRegistry codecRegistry) {
        this.codecRegistry = codecRegistry;
    }

    @Override
    public Class<Part> getAnnotationType() {
        return Part.class;
    }

    @Override
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        if (source instanceof ServletExchange) {
            ServletExchange<?, ?> exchange = (ServletExchange<?, ?>) source;
            final HttpServletRequest nativeRequest = (HttpServletRequest) exchange.getRequest().getNativeRequest();
            final Argument<T> argument = context.getArgument();
            final String partName = context.getAnnotationMetadata().stringValue(Part.class).orElse(argument.getName());
            final jakarta.servlet.http.Part part;
            try {
                part = nativeRequest.getPart(partName);
            } catch (IOException | ServletException e) {
                throw new InternalServerException("Error reading part [" + partName + "]: " + e.getMessage(), e);
            }
            if (part != null) {
                final Class<T> type = argument.getType();
                if (jakarta.servlet.http.Part.class.isAssignableFrom(type)) {
                    //noinspection unchecked
                    return () -> (Optional<T>) Optional.of(part);
                } else if (Readable.class.isAssignableFrom(type)) {
                    //noinspection unchecked
                    return () -> (Optional<T>) Optional.of(new Readable() {
                        @NonNull
                        @Override
                        public String getName() {
                            return part.getName();
                        }

                        @Override
                        public Reader asReader() throws IOException {
                            final Charset charset = Optional.ofNullable(part.getContentType()).map(MediaType::new)
                                    .flatMap(MediaType::getCharset).orElse(StandardCharsets.UTF_8);
                            return new InputStreamReader(asInputStream(), charset);
                        }

                        @NonNull
                        @Override
                        public InputStream asInputStream() throws IOException {
                            return part.getInputStream();
                        }

                        @Override
                        public boolean exists() {
                            return true;
                        }
                    });
                } else if (String.class.isAssignableFrom(type)) {
                    try (BufferedReader reader = newReader(part)) {
                        final String content = IOUtils.readText(reader);
                        return () -> (Optional<T>) Optional.of(content);
                    } catch (IOException e) {
                        throw new HttpStatusException(
                                HttpStatus.BAD_REQUEST,
                                "Unable to read part [" + partName + "]: " + e.getMessage()
                        );
                    }
                } else if (byte[].class.isAssignableFrom(type)) {
                    try (InputStream is = part.getInputStream()) {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

                        int nRead;
                        byte[] data = new byte[16384];

                        while ((nRead = is.read(data, 0, data.length)) != -1) {
                            buffer.write(data, 0, nRead);
                        }
                        final byte[] content = buffer.toByteArray();
                        return () -> (Optional<T>) Optional.of(content);
                    } catch (IOException e) {
                        throw new HttpStatusException(
                                HttpStatus.BAD_REQUEST,
                                "Unable to read part [" + partName + "]: " + e.getMessage()
                        );
                    }

                } else if (CompletedFileUpload.class.isAssignableFrom(type)) {
                    //noinspection unchecked
                    return () -> (Optional<T>) Optional.of(new ServletCompletedFileUpload(part));
                } else {
                    final MediaType contentType =
                            Optional.ofNullable(part.getContentType()).map(MediaType::new)
                            .orElse(null);
                    if (contentType != null) {
                        final MediaTypeCodec codec = codecRegistry.findCodec(contentType, type).orElse(null);
                        if (codec != null) {
                            try (InputStream inputStream = part.getInputStream()) {
                                final T content = codec.decode(argument, inputStream);
                                return () -> (Optional<T>) Optional.of(content);
                            } catch (IOException e) {
                                throw new HttpStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Unable to read part [" + partName + "]: " + e.getMessage()
                                );
                            }
                        }
                    }
                }
            }
        }
        return BindingResult.UNSATISFIED;
    }

    private BufferedReader newReader(jakarta.servlet.http.Part part) throws IOException {
        final Charset charset = Optional.ofNullable(part.getContentType())
                .map(MediaType::new)
                .flatMap(MediaType::getCharset)
                .orElse(StandardCharsets.UTF_8);
        final InputStreamReader inputStreamReader = new InputStreamReader(part.getInputStream(), charset);
        return new BufferedReader(inputStreamReader);
    }

}
