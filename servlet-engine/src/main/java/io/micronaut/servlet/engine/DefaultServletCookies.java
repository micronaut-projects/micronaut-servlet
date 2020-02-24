package io.micronaut.servlet.engine;

import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.Cookies;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of {@link Cookies} ontop of the Servlet API.
 *
 * @since 1.0.0
 * @author graemerocher
 */
public class DefaultServletCookies implements Cookies {

    private final javax.servlet.http.Cookie[] cookies;

    public DefaultServletCookies(javax.servlet.http.Cookie[] cookies) {
        this.cookies = cookies;
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
            javax.servlet.http.Cookie cookie = cookies[i];
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
    public <T> Optional<T> get(CharSequence name, ArgumentConversionContext<T> conversionContext) {
        return findCookie(name)
                .flatMap(cookie -> ConversionService.SHARED.convert(cookie.getValue(), conversionContext));
    }

}
