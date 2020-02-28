package io.micronaut.servlet.http;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import io.micronaut.http.codec.MediaTypeCodecRegistry;

import java.util.Optional;

/**
 * A {@link io.micronaut.http.bind.binders.RequestArgumentBinder} that can bind the HTTP request
 * for a {@link ServletHttpRequest} including resolving any type arguments for the body.
 *
 * @author graemerocher
 * @since 2.0.0
 */
@Internal
class ServletRequestBinder implements TypedRequestArgumentBinder<HttpRequest> {

    private final MediaTypeCodecRegistry mediaTypeCodecRegistry;

    /**
     * Default constructor.
     *
     * @param mediaTypeCodecRegistry The media type code registry
     */
    ServletRequestBinder(MediaTypeCodecRegistry mediaTypeCodecRegistry) {
        this.mediaTypeCodecRegistry = mediaTypeCodecRegistry;
    }

    @Override
    public Argument<HttpRequest> argumentType() {
        return Argument.of(HttpRequest.class);
    }

    @Override
    public BindingResult<HttpRequest> bind(ArgumentConversionContext<HttpRequest> context, HttpRequest<?> source) {
        if (source instanceof ServletHttpRequest) {
            ServletHttpRequest<?, ?> serverlessHttpRequest = (ServletHttpRequest<?, ?>) source;
            long contentLength = serverlessHttpRequest.getContentLength();
            final Argument<?> bodyType = context.getArgument().getFirstTypeVariable().orElse(null);
            if (bodyType != null && contentLength != 0) {
                return () -> Optional.of(
                        new ServletRequestAndBody(serverlessHttpRequest, bodyType)
                );
            }

        }
        return () -> Optional.of(source);
    }
}
