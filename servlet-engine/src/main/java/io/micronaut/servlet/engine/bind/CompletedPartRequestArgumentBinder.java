package io.micronaut.servlet.engine.bind;

import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.servlet.engine.ServletCompletedFileUpload;
import io.micronaut.servlet.http.ServletExchange;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Optional;

/**
 * Binder for {@link CompletedPart}.
 *
 * @author graemerocher
 * @since 1.0.0
 */
class CompletedPartRequestArgumentBinder implements TypedRequestArgumentBinder<CompletedPart> {
    @Override
    public BindingResult<CompletedPart> bind(
            ArgumentConversionContext<CompletedPart> context,
            HttpRequest<?> source) {
        ServletExchange<?, ?> exchange = (ServletExchange<?, ?>) source;
        final HttpServletRequest nativeRequest = (HttpServletRequest) exchange.getRequest().getNativeRequest();
        final Argument<?> argument = context.getArgument();
        final String partName = context.getAnnotationMetadata().stringValue(Part.class).orElse(argument.getName());
        try {
            javax.servlet.http.Part part = nativeRequest.getPart(partName);
            return () -> Optional.of(new ServletCompletedFileUpload(part));
        } catch (IOException | ServletException e) {
            throw new InternalServerException("Error reading part [" + partName + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public Argument<CompletedPart> argumentType() {
        return Argument.of(CompletedPart.class);
    }
}
