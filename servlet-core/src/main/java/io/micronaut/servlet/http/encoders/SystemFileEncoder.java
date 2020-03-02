package io.micronaut.servlet.http.encoders;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.server.types.files.SystemFile;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletHttpRequest;
import io.micronaut.servlet.http.ServletHttpResponse;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import org.reactivestreams.Publisher;

import javax.annotation.Nonnull;
import javax.inject.Singleton;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/**
 * A {@link io.micronaut.servlet.http.ServletResponseEncoder} for {@link SystemFile}.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Singleton
public class SystemFileEncoder extends AbstractFileEncoder<SystemFile> {
    @Override
    public Class<SystemFile> getResponseType() {
        return SystemFile.class;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> encode(
            @Nonnull ServletExchange<?, ?> exchange,
            AnnotationMetadata annotationMetadata,
            @Nonnull SystemFile value) {
        final ServletHttpRequest<?, ? super Object> request = exchange.getRequest();
        ServletHttpResponse<?, ? super Object> response = exchange.getResponse();
        if (ifNotModified(value, request, response)) {
            return Publishers.just(
                    setDateHeader(
                            response.status(HttpStatus.NOT_MODIFIED)
                    )

            );
        }
        boolean asyncSupported = exchange.getRequest().isAsyncSupported();
        if (asyncSupported) {
            final RandomAccessFile randomAccessFile;
            try {
                randomAccessFile = new RandomAccessFile(value.getFile(), "r");
                return response.stream(Flowable.create(emitter -> {
                    ByteBuffer buf = ByteBuffer.allocate(1024);
                    try (FileChannel channel = randomAccessFile.getChannel()) {
                        while (channel.read(buf) > 0) {
                            final byte[] bytes = buf.array();
                            final int p = buf.position();
                            if (p == 1024) {
                                emitter.onNext(buf.array());
                            } else {
                                emitter.onNext(Arrays.copyOf(bytes, p));
                            }
                            buf.clear();
                        }
                    } catch (Throwable e) {
                        emitter.onError(e);
                    }
                }, BackpressureStrategy.BUFFER));
            } catch (FileNotFoundException e) {
                return Publishers.just(
                        response.status(HttpStatus.NOT_FOUND)
                );
            }
        } else {
            return Flowable.fromCallable(() -> {
                try (InputStream in = new FileInputStream(value.getFile())) {
                    try (OutputStream out = response.getOutputStream()) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            out.write(buffer, 0, len);
                        }
                    }
                }
                return response;
            });
        }
    }
}
