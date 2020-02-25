package io.micronaut.servlet.engine.bind;

import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import io.micronaut.servlet.http.ServletHttpRequest;

import javax.servlet.http.HttpServletRequest;
import java.util.Optional;

public class ServletRequestBinder implements TypedRequestArgumentBinder<HttpServletRequest> {

    public static final Argument<HttpServletRequest> TYPE = Argument.of(HttpServletRequest.class);

    @Override
    public Argument<HttpServletRequest> argumentType() {
        return TYPE;
    }

    @Override
    public BindingResult<HttpServletRequest> bind(
            ArgumentConversionContext<HttpServletRequest> context,
            HttpRequest<?> source) {
        if (source instanceof ServletHttpRequest) {
            ServletHttpRequest servletRequest = (ServletHttpRequest) source;
            return () -> Optional.of((HttpServletRequest) servletRequest.getNativeRequest());
        }
        return BindingResult.UNSATISFIED;
    }
}
