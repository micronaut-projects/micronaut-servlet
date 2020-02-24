package io.micronaut.servlet.http;

import io.micronaut.core.util.ArgumentUtils;

/**
 * Represents an HTTP exchange in a Serverless context.
 *
 * @param <Req> The native request type
 * @param <Res> The native response type
 * @author graemerocher
 * @since 2.0.0
 */
public class DefaultServletExchange<Req, Res> implements ServletExchange<Req, Res> {

    private final ServletHttpRequest<Req, ? super Object> request;
    private final ServletHttpResponse<Res, ? super Object> response;

    /**
     * Default constructor.
     *
     * @param request  The request
     * @param response The response
     */
    public DefaultServletExchange(
            ServletHttpRequest<Req, ? super Object> request,
            ServletHttpResponse<Res, ? super Object> response) {
        ArgumentUtils.requireNonNull("request", request);
        ArgumentUtils.requireNonNull("response", response);
        this.request = request;
        this.response = response;
    }

    /**
     * @return The request object
     */
    public ServletHttpRequest<Req, ? super Object> getRequest() {
        return request;
    }

    /**
     * @return The response object
     */
    public ServletHttpResponse<Res, ? super Object> getResponse() {
        return response;
    }
}
