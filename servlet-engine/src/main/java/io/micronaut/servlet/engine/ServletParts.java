/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.servlet.engine;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.ContentLengthExceededException;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.Collection;
import java.util.Locale;

/**
 * Reads the parts of a multipart request from the container, which enforces the limits of its
 * {@code MultipartConfigElement}: the Servlet specification has {@link HttpServletRequest#getParts()}
 * and {@link HttpServletRequest#getPart(String)} throw an {@link IllegalStateException} when the
 * request or one of its parts is larger than they allow, which is answered with
 * {@code 413 Request Entity Too Large} like a body over the size limit of the server, and not with
 * {@code 500}.
 *
 * @author Denis Stepanov
 * @since 6.3.0
 */
@Internal
public final class ServletParts {

    private ServletParts() {
    }

    /**
     * @param request The request
     * @return Its parts
     * @throws IOException      If the parts cannot be read
     * @throws ServletException If the request is not a multipart request
     */
    public static Collection<Part> parts(HttpServletRequest request) throws IOException, ServletException {
        requireBoundary(request);
        try {
            return request.getParts();
        } catch (IllegalStateException e) {
            throw tooLarge(e);
        } catch (IOException e) {
            throw tooLargeOr(e);
        } catch (ServletException e) {
            throw tooLargeOr(e);
        }
    }

    /**
     * @param request The request
     * @param name    The name of the part
     * @return The part, or {@code null} if the request has none of that name
     * @throws IOException      If the parts cannot be read
     * @throws ServletException If the request is not a multipart request
     */
    public static @Nullable Part part(HttpServletRequest request, String name) throws IOException, ServletException {
        requireBoundary(request);
        try {
            return request.getPart(name);
        } catch (IllegalStateException e) {
            throw tooLarge(e);
        } catch (IOException e) {
            throw tooLargeOr(e);
        } catch (ServletException e) {
            throw tooLargeOr(e);
        }
    }

    /**
     * A multipart request without a boundary cannot be parsed: it is a bad request, which the containers answer
     * each in their own way, with 500, 413 or no parts at all.
     */
    private static void requireBoundary(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType != null
            && contentType.regionMatches(true, 0, "multipart/", 0, "multipart/".length())
            && !contentType.toLowerCase(Locale.ROOT).contains("boundary=")) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "The multipart request has no boundary");
        }
    }

    private static ContentLengthExceededException tooLarge(Throwable e) {
        return new ContentLengthExceededException("The multipart request exceeds the size the server allows: " + e.getMessage(), e);
    }

    /**
     * Jetty reports a part over its limit as a bad multipart message, with the
     * {@link IllegalStateException} of the limit as its cause.
     */
    private static <E extends Exception> E tooLargeOr(E e) {
        for (Throwable cause = e.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof IllegalStateException) {
                throw tooLarge(cause);
            }
        }
        return e;
    }
}
