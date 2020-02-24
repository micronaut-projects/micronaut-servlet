package io.micronaut.servlet.http;

/**
 * Represents an HTTP exchange in a serverless context.
 *
 * @param <Req> The native request type
 * @param <Res> The native response type
 * @author graemerocher
 * @since 2.0.0
 */
public interface ServletExchange<Req, Res> {

    /**
     * @return The request object
     */
    ServletHttpRequest<Req, ? super Object> getRequest();

    /**
     * @return The response object
     */
    ServletHttpResponse<Res, ? super Object> getResponse();
}
