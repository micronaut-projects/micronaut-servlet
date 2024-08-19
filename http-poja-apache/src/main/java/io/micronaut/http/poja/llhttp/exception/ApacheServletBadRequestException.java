package io.micronaut.http.poja.llhttp.exception;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.server.exceptions.HttpServerException;

/**
 * An exception that gets thrown in case of a bad request sent by user.
 * The exception is specific to Apache request parsing.
 */
@Internal
public final class ApacheServletBadRequestException extends HttpServerException {

    /**
     * Create an apache bad request exception.
     *
     * @param message The message to send to user
     * @param cause The cause
     */
    public ApacheServletBadRequestException(String message, Exception cause) {
        super(message, cause);
    }

}
