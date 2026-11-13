/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.servlet.http.server.jdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsExchange;
import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.cookie.ServerCookieDecoder;
import io.micronaut.http.form.FormUrlEncodedDecoder;
import io.micronaut.http.uri.QueryStringDecoder;
import io.micronaut.http.util.HttpHeadersUtil;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.micronaut.http.util.HttpHeadersUtil.parseCharacterEncoding;

/**
 * Implementation of {@link HttpServletRequest} backed with a {@link HttpExchange}.
 */
@Experimental
@Internal
final class HttpExchangeHttpServletRequest implements HttpServletRequest {
    private static final Logger LOG = LoggerFactory.getLogger(HttpExchangeHttpServletRequest.class);
    private final HttpExchange exchange;
    private final ServletInputStream inputStream;
    private final FormUrlEncodedDecoder formUrlEncodedDecoder;
    private Map<String, Object> attributes = new HashMap<>();
    private Map<String, String[]>  parameters = null;
    private @Nullable Authority authority;
    private boolean authorityParsed;

    HttpExchangeHttpServletRequest(HttpExchange httpExchange,
                                   FormUrlEncodedDecoder formUrlEncodedDecoder) {
        this.exchange = httpExchange;
        this.formUrlEncodedDecoder = formUrlEncodedDecoder;
        this.inputStream = new HttpExchangeServletInputStream(exchange);
    }

    @Override
    public Cookie[] getCookies() {
        // a client may split its cookies over several Cookie headers, so all of them are decoded, not just the first
        List<String> cookieHeaders = exchange.getRequestHeaders().get(HttpHeaders.COOKIE);
        if (CollectionUtils.isEmpty(cookieHeaders)) {
            return new Cookie[0];
        }
        String cookieValue = String.join("; ", cookieHeaders);
        if (StringUtils.isEmpty(cookieValue)) {
            return new Cookie[0];
        }
        List<io.micronaut.http.cookie.Cookie> cookies = ServerCookieDecoder.INSTANCE.decode(cookieValue);
        if (CollectionUtils.isEmpty(cookies)) {
            return new Cookie[0];
        }
        Cookie[] result = new Cookie[cookies.size()];
        for (int i = 0; i < cookies.size(); i++) {
            io.micronaut.http.cookie.Cookie httpCookie = cookies.get(i);
            result[i] = cookie(httpCookie);
        }
        return result;
    }

    private static Cookie cookie(io.micronaut.http.cookie.Cookie cookie) {
        Cookie c = new Cookie(cookie.getName(), cookie.getValue());
        c.setDomain(cookie.getDomain());
        c.setHttpOnly(cookie.isHttpOnly());
        c.setPath(cookie.getPath());
        c.setSecure(cookie.isSecure());
        c.setMaxAge((int) cookie.getMaxAge());
        return c;
    }

    @Override
    public String getHeader(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        if (CollectionUtils.isEmpty(values)) {
            return Collections.emptyEnumeration();
        }
        return Collections.enumeration(values);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(exchange.getRequestHeaders().keySet());
    }

    @Override
    public int getIntHeader(String name) {
        String header = getHeader(name);
        return header == null ? -1 : Integer.parseInt(header);
    }

    @Override
    public String getMethod() {
        return exchange.getRequestMethod();
    }

    @Override
    public String getQueryString() {
        // raw, as the Servlet API specifies: the engine decodes it when it parses the request URI
        return exchange.getRequestURI().getRawQuery();
    }

    @Override
    public String getRequestURI() {
        // the Servlet API defines the request URI as the raw, still percent-encoded path. The decoded form would be
        // decoded a second time when the engine parses it into a URI, and an encoded '?', '#' or '/' would change
        // the structure of the request
        String rawPath = exchange.getRequestURI().getRawPath();
        return StringUtils.isEmpty(rawPath) ? "/" : rawPath;
    }

    @Override
    public StringBuffer getRequestURL() {
        StringBuffer url = new StringBuffer();
        String scheme = getScheme();
        int port = getServerPort();
        url.append(scheme).append("://").append(hostForUrl(getServerName()));
        if (port > 0 && !isDefaultPort(scheme, port)) {
            url.append(':').append(port);
        }
        return url.append(getRequestURI());
    }

    private static String hostForUrl(String host) {
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
    }

    /**
     * Whether the {@code Host} header is acceptable: absent or empty, or a single header holding a host name, an IPv4
     * address or a bracketed IPv6 address, with an optional port. The JDK server does not check it, and anything else
     * would be reflected into {@link #getServerName()} and {@link #getRequestURL()}.
     *
     * @return {@code false} if the request is to be rejected with a 400, as Tomcat and Jetty do
     */
    boolean hasValidHostHeader() {
        List<String> hosts = exchange.getRequestHeaders().get(HttpHeaders.HOST);
        if (CollectionUtils.isEmpty(hosts)) {
            return true;
        }
        if (hosts.size() > 1) {
            return false;
        }
        return hosts.get(0).isBlank() || authority() != null;
    }

    /**
     * @return The host and port from the {@code Host} header, parsed once per request, or {@code null} if there is no
     * usable one
     */
    private @Nullable Authority authority() {
        if (!authorityParsed) {
            authority = Authority.parse(exchange.getRequestHeaders().get(HttpHeaders.HOST));
            authorityParsed = true;
        }
        return authority;
    }

    /**
     * The host and port of a {@code Host} header.
     *
     * @param host The host name or address, IPv6 addresses without their brackets
     * @param port The port, or -1 if the header has none
     */
    private record Authority(String host, int port) {

        /**
         * A reg-name restricted to the characters host names use, an IPv4 address, or a bracketed IPv6 address,
         * followed by an optional port. Deliberately stricter than RFC 3986, which also allows sub-delimiters and
         * percent-encoding in a reg-name: those have no place in a host name and would only be reflected into URLs.
         */
        private static final Pattern HOST_HEADER = Pattern.compile("(\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9._-]+)(?::([0-9]{0,5}))?");
        private static final int MAX_PORT = 65535;

        static @Nullable Authority parse(@Nullable List<String> hosts) {
            if (hosts == null || hosts.size() != 1) {
                return null;
            }
            Matcher matcher = HOST_HEADER.matcher(hosts.get(0).trim());
            if (!matcher.matches()) {
                return null;
            }
            String host = matcher.group(1);
            if (host.startsWith("[")) {
                host = host.substring(1, host.length() - 1);
                if (!isIpv6Literal(host)) {
                    return null;
                }
            }
            String portText = matcher.group(2);
            int port = -1;
            if (StringUtils.isNotEmpty(portText)) {
                port = Integer.parseInt(portText);
                if (port > MAX_PORT) {
                    return null;
                }
            }
            return new Authority(host, port);
        }

        /**
         * @param address The contents of the brackets
         * @return Whether they are an IPv6 address. Left to {@link URI}, which checks the syntax without resolving
         * anything
         */
        private static boolean isIpv6Literal(String address) {
            try {
                return URI.create("http://[" + address + "]/").getHost() != null;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public String getCharacterEncoding() {
        return parseCharacterEncoding(getHeader(HttpHeaders.CONTENT_TYPE), getHeader(HttpHeaders.ACCEPT_CHARSET)).toString();
    }

    @Override
    public int getContentLength() {
        String headerValue = getHeader(HttpHeaders.CONTENT_LENGTH);
        if (StringUtils.isEmpty(headerValue)) {
            return 0;
        }
        return Integer.valueOf(headerValue);
    }

    @Override
    public long getContentLengthLong() {
        String headerValue = getHeader(HttpHeaders.CONTENT_LENGTH);
        if (StringUtils.isEmpty(headerValue)) {
            return 0L;
        }
        return Long.valueOf(headerValue);
    }

    @Override
    public String getContentType() {
        return exchange.getRequestHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        return inputStream;
    }

    @Override
    public String getParameter(String name) {
        String[] values = getParameterValues(name);
        return values == null ? null : values[0];
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(getParameterMap().keySet());
    }

    @Override
    public String[] getParameterValues(String name) {
        return getParameterMap().get(name);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        if (parameters == null) {
            Map<String, Object> formParameters = null;
            if (isFormSubmission()) {
                try {
                    String formBody = new String(getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    formParameters = formUrlEncodedDecoder.decode(formBody, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    LOG.error("could not decode form url encoded body", e);
                }
            } else {
                formParameters = Collections.emptyMap();
            }
            Map<String, List<String>> params = new QueryStringDecoder(exchange.getRequestURI()).parameters();
            parameters = mergeParams(formParameters, params);

        }
        return parameters;
    }

    @Override
    public String getProtocol() {
        return exchange.getProtocol();
    }

    @Override
    public String getScheme() {
        return exchange instanceof HttpsExchange ? "https" : "http";
    }

    @Override
    public String getServerName() {
        // as the Servlet API specifies, the server name comes from the Host header. Falling back to the local address
        // uses the host string rather than getHostName(), which would do a reverse DNS lookup on every request
        Authority authority = authority();
        return authority != null ? authority.host() : exchange.getLocalAddress().getHostString();
    }

    @Override
    public int getServerPort() {
        // the Servlet API: the port of the Host header if it has one, otherwise the port the connection was accepted
        // on, which is also what Tomcat reports
        Authority authority = authority();
        return authority != null && authority.port() > 0 ? authority.port() : exchange.getLocalAddress().getPort();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(exchange.getRequestBody()));
    }

    @Override
    public String getRemoteAddr() {
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    @Override
    public String getRemoteHost() {
        // no reverse DNS lookup: a client with a slow PTR record could otherwise hold up handler threads. This is
        // what servlet containers do with host name lookups disabled, their default
        return getRemoteAddr();
    }

    @Override
    public void setAttribute(String name, Object o) {
        this.attributes.put(name, o);
    }

    @Override
    public void removeAttribute(String name) {
        this.attributes.remove(name);
    }

    @Override
    public Locale getLocale() {
        return Optional.ofNullable(exchange.getRequestHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE))
            .map(text -> {
                String part = HttpHeadersUtil.splitAcceptHeader(text);
                return part == null ? Locale.getDefault() : Locale.forLanguageTag(part);
            }).orElseGet(Locale::getDefault);
    }

    @Override
    public boolean isSecure() {
        return exchange instanceof HttpsExchange;
    }

    @Override
    public int getRemotePort() {
        return exchange.getRemoteAddress().getPort();
    }

    @Override
    public String getLocalName() {
        // the host string avoids a reverse DNS lookup per request
        return exchange.getLocalAddress().getHostString();
    }

    @Override
    public String getLocalAddr() {
        return exchange.getLocalAddress().getAddress().getHostAddress();
    }

    @Override
    public int getLocalPort() {
        return exchange.getLocalAddress().getPort();
    }

    @Override
    public boolean isAsyncSupported() {
        return false;
    }

    @Override
    public String getAuthType() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Enumeration<Locale> getLocales() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public long getDateHeader(String name) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String getPathInfo() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String getPathTranslated() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String getContextPath() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String getRemoteUser() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean isUserInRole(String role) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Principal getUserPrincipal() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String getRequestedSessionId() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String getServletPath() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public HttpSession getSession(boolean create) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public HttpSession getSession() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String changeSessionId() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean authenticate(HttpServletResponse httpServletResponse) throws IOException, ServletException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void login(String s, String s1) throws ServletException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void logout() throws ServletException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Part getPart(String s) throws IOException, ServletException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> aClass) throws IOException, ServletException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void setCharacterEncoding(String env) throws UnsupportedEncodingException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public ServletContext getServletContext() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean isAsyncStarted() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public AsyncContext getAsyncContext() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public DispatcherType getDispatcherType() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String getRequestId() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String getProtocolRequestId() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public ServletConnection getServletConnection() {
        throw new UnsupportedOperationException("Not implemented");
    }

    private boolean isFormSubmission() {
        String contentType = getContentType();

        if (StringUtils.isEmpty(contentType)) {
            return false;
        }
        MediaType mediaType = MediaType.of(contentType);
        if (mediaType == null) {
            return false;
        }
        return isFormSubmission(mediaType);
    }

    private static boolean isFormSubmission(MediaType contentType) {
        return MediaType.APPLICATION_FORM_URLENCODED_TYPE.equals(contentType) || MediaType.MULTIPART_FORM_DATA_TYPE.equals(contentType);
    }

    private static Map<String, String[]> mergeParams(Map<String, Object> map1, Map<String, List<String>> map2) {
        Map<String, String[]> mergedMap = new HashMap<>();

        for (Map.Entry<String, Object> entry : map1.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String[]) {
                mergedMap.put(key, (String[]) value);
            } else if (value instanceof String) {
                mergedMap.put(key, new String[]{(String) value});
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                String[] array = list.toArray(new String[0]);
                mergedMap.put(key, array);
            } else {
                if (value != null) {
                    mergedMap.put(key, new String[]{value.toString()});
                }
            }
        }

        for (Map.Entry<String, List<String>> entry : map2.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            String[] valuesArray = values.toArray(new String[0]);

            if (mergedMap.containsKey(key)) {
                String[] existingValues = mergedMap.get(key);
                String[] newValues = new String[existingValues.length + valuesArray.length];
                System.arraycopy(existingValues, 0, newValues, 0, existingValues.length);
                System.arraycopy(valuesArray, 0, newValues, existingValues.length, valuesArray.length);
                mergedMap.put(key, newValues);
            } else {
                mergedMap.put(key, valuesArray);
            }
        }

        return mergedMap;
    }
}
