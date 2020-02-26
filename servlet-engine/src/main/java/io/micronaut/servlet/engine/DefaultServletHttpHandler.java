package io.micronaut.servlet.engine;

import io.micronaut.context.ApplicationContext;
import io.micronaut.servlet.http.ServletExchange;
import io.micronaut.servlet.http.ServletHttpHandler;

import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Singleton
public class DefaultServletHttpHandler extends ServletHttpHandler<HttpServletRequest, HttpServletResponse> {
    /**
     * Default constructor.
     *
     * @param applicationContext The application context
     */
    public DefaultServletHttpHandler(ApplicationContext applicationContext) {
        super(applicationContext);
    }

    @Override
    protected ServletExchange<HttpServletRequest, HttpServletResponse> createExchange(
            HttpServletRequest request,
            HttpServletResponse response) {
        return new DefaultServletHttpRequest<>(request, response, getMediaTypeCodecRegistry());
    }

    @Override
    public void service(HttpServletRequest request, HttpServletResponse response) {
        final ServletExchange<HttpServletRequest, HttpServletResponse> exchange = createExchange(request, response);
        service(exchange);
    }
}
