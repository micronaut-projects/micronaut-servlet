package io.micronaut.servlet.engine.bind;

import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import io.micronaut.servlet.http.ServletExchange;

import javax.servlet.http.HttpServletResponse;
import java.util.Optional;

/**
 * A binder capable of binding the servlet response.
 *
 * @author graemerocher
 * @since 1.0.0
 *
 */
public class ServletResponseBinder implements TypedRequestArgumentBinder<HttpServletResponse> {

    public static final Argument<HttpServletResponse> TYPE = Argument.of(HttpServletResponse.class);

    @Override
    public Argument<HttpServletResponse> argumentType() {
        return TYPE;
    }

    @Override
    public BindingResult<HttpServletResponse> bind(ArgumentConversionContext<HttpServletResponse> context, HttpRequest<?> source) {
        if (source instanceof ServletExchange) {
            ServletExchange servletRequest = (ServletExchange) source;
            return () -> Optional.of((HttpServletResponse) servletRequest.getResponse().getNativeResponse());
        }
        return BindingResult.UNSATISFIED;
    }
}
