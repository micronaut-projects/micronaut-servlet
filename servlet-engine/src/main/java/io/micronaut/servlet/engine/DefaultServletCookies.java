/*
 * Copyright 2017-2020 original authors
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

import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.Cookies;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of {@link Cookies} ontop of the Servlet API.
 *
 * @since 1.0.0
 * @author graemerocher
 */
public class DefaultServletCookies implements Cookies {

    private static final jakarta.servlet.http.Cookie[] EMPTY_COOKIES = new jakarta.servlet.http.Cookie[0];

    private final jakarta.servlet.http.Cookie[] cookies;

    /**
     * Default constructor.
     * @param cookies The cookies
     */
    public DefaultServletCookies(jakarta.servlet.http.Cookie[] cookies) {
        if (cookies == null) {
            this.cookies = EMPTY_COOKIES;
        } else {
            this.cookies = cookies;
        }
    }

    @Override
    public Set<Cookie> getAll() {
        return Arrays.stream(cookies)
                .map(ServletCookieAdapter::new)
                .collect(Collectors.toSet());
    }

    @Override
    public Optional<Cookie> findCookie(CharSequence name) {
        final String cookieName = Objects.requireNonNull(name, "Cookie name cannot be null").toString();
        for (int i = 0; i < cookies.length; i++) {
            jakarta.servlet.http.Cookie cookie = cookies[i];
            if (cookie.getName().equals(cookieName)) {
                return Optional.of(new ServletCookieAdapter(cookie));
            }
        }
        return Optional.empty();
    }

    @Override
    public Collection<Cookie> values() {
        return getAll();
    }

    @Override
    public <T> Optional<T> get(CharSequence name, Class<T> requiredType) {
        if (requiredType == Cookie.class || requiredType == Object.class) {
            //noinspection unchecked
            return (Optional<T>) findCookie(name);
        } else {
            return findCookie(name).flatMap((cookie -> ConversionService.SHARED.convert(cookie.getValue(), requiredType)));
        }
    }

    @Override
    public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
        return findCookie(name).flatMap((cookie -> ConversionService.SHARED.convert(cookie.getValue(), conversionContext)));
    }
}
