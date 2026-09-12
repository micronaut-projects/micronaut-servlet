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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;

import java.util.Objects;
import java.util.Optional;

/**
 * Adapts the Servlet Cookie API to {@link Cookie}.
 *
 * @author graemerocher
 * @since 1.0
 */
public final class ServletCookieAdapter implements Cookie {
    private final jakarta.servlet.http.Cookie cookie;

    /**
     * Default constructor.
     * @param cookie The servlet cookie to adapt.
     */
    public ServletCookieAdapter(jakarta.servlet.http.Cookie cookie) {
        this.cookie = cookie;
    }

    /**
     * @return The backing servlet cookie
     */
    public jakarta.servlet.http.Cookie getCookie() {
        return cookie;
    }

    @NonNull
    @Override
    public String getName() {
        return cookie.getName();
    }

    @NonNull
    @Override
    public String getValue() {
        return cookie.getValue();
    }

    @Nullable
    @Override
    public String getDomain() {
        return cookie.getDomain();
    }

    @Nullable
    @Override
    public String getPath() {
        return cookie.getPath();
    }

    @Override
    public boolean isHttpOnly() {
        return cookie.isHttpOnly();
    }

    @Override
    public boolean isSecure() {
        return cookie.getSecure();
    }

    @Override
    public long getMaxAge() {
        return cookie.getMaxAge();
    }

    @NonNull
    @Override
    public Cookie maxAge(long maxAge) {
        cookie.setMaxAge((int) maxAge);
        return this;
    }

    @NonNull
    @Override
    public Cookie value(@NonNull String value) {
        cookie.setValue(Objects.requireNonNull(value, "Value cannot be null"));
        return this;
    }

    @NonNull
    @Override
    public Cookie domain(@Nullable String domain) {
        if (domain != null) {
            cookie.setDomain(domain);
        }
        return this;
    }

    @NonNull
    @Override
    public Cookie path(@Nullable String path) {
        if (path != null) {
            cookie.setPath(path);
        }
        return this;
    }

    @NonNull
    @Override
    public Cookie secure(boolean secure) {
        cookie.setSecure(secure);
        return this;
    }

    @NonNull
    @Override
    public Cookie httpOnly(boolean httpOnly) {
        cookie.setHttpOnly(httpOnly);
        return this;
    }

    @NonNull
    @Override
    public Cookie sameSite(@Nullable SameSite sameSite) {
        cookie.setAttribute(Cookie.ATTRIBUTE_SAME_SITE, sameSite == null ? null : sameSite.name());
        return this;
    }

    @Override
    public Optional<SameSite> getSameSite() {
        String value = cookie.getAttribute(Cookie.ATTRIBUTE_SAME_SITE);
        if (value == null) {
            return Optional.empty();
        }
        for (SameSite candidate : SameSite.values()) {
            if (candidate.name().equalsIgnoreCase(value)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    @Override
    public int compareTo(Cookie o) {
        Objects.requireNonNull(o, "Cookie to compare to cannot be null");
        // name, then path, then domain: the triple that identifies a cookie per RFC 6265
        int v = getName().compareTo(o.getName());
        if (v != 0) {
            return v;
        }

        if (getPath() == null) {
            if (o.getPath() != null) {
                return -1;
            }
        } else if (o.getPath() == null) {
            return 1;
        } else {
            v = getPath().compareTo(o.getPath());
            if (v != 0) {
                return v;
            }
        }

        if (getDomain() == null) {
            return o.getDomain() == null ? 0 : -1;
        } else if (o.getDomain() == null) {
            return 1;
        } else {
            return getDomain().compareToIgnoreCase(o.getDomain());
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        // deliberately not any Cookie: an implementation that inherits Object.equals would make this asymmetric,
        // which breaks the contract and makes Set and Map behaviour depend on argument order
        if (!(o instanceof ServletCookieAdapter other)) {
            return false;
        }
        return getName().equals(other.getName())
            && Objects.equals(getPath(), other.getPath())
            && (getDomain() == null ? other.getDomain() == null : getDomain().equalsIgnoreCase(other.getDomain()));
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            getName(),
            getPath(),
            getDomain() == null ? null : getDomain().toLowerCase(java.util.Locale.ROOT)
        );
    }
}
