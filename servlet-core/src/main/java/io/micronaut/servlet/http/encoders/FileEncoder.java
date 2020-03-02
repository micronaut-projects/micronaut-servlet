package io.micronaut.servlet.http.encoders;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.server.types.files.SystemFile;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletResponseEncoder;
import org.reactivestreams.Publisher;

import javax.annotation.Nonnull;
import javax.inject.Singleton;
import java.io.File;

/**
 * Handles {@link File}.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Singleton
public class FileEncoder implements ServletResponseEncoder<File> {
    @Override
    public Class<File> getResponseType() {
        return File.class;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> encode(@Nonnull ServletExchange<?, ?> exchange, AnnotationMetadata annotationMetadata, @Nonnull File value) {
        return new SystemFileEncoder().encode(exchange, annotationMetadata, new SystemFile(value));
    }
}
