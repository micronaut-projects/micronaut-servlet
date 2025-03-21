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
import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MediaType;
import io.micronaut.http.cookie.ServerCookieDecoder;
import io.micronaut.http.form.FormUrlEncodedDecoder;
import io.micronaut.http.util.HttpHeadersUtil;
import io.micronaut.servlet.http.utils.QueryStringDecoder;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;

/**
 * Implementation of {@link HttpServletRequest} backed with a {@link HttpExchange}.
 */
@Experimental
@Internal
class HttpExchangeHttpServletRequest implements HttpServletRequest {
    private static final Logger LOG = LoggerFactory.getLogger(HttpExchangeHttpServletRequest.class);
    private final HttpExchange exchange;
    private final ServletInputStream inputStream;
    private final FormUrlEncodedDecoder formUrlEncodedDecoder;
    private Map<String, Object> attributes = new HashMap<>();
    private Map<String, String[]>  parameters = null;

    HttpExchangeHttpServletRequest(HttpExchange httpExchange,
                                   FormUrlEncodedDecoder formUrlEncodedDecoder) {
        this.exchange = httpExchange;
        this.formUrlEncodedDecoder = formUrlEncodedDecoder;
        this.inputStream = new HttpExchangeServletInputStream(exchange);
    }

    @Override
    public Cookie[] getCookies() {
        String cookieValue = exchange.getRequestHeaders().getFirst(HttpHeaders.COOKIE);
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
        return exchange.getRequestURI().getQuery();
    }

    @Override
    public String getRequestURI() {
        return exchange.getRequestURI().getPath();
    }

    @Override
    public StringBuffer getRequestURL() {
        return new StringBuffer(exchange.getRequestURI().toString());
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
        return exchange.getRequestHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
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
        return exchange.getRequestURI().getScheme();
    }

    @Override
    public String getServerName() {
        return exchange.getLocalAddress().getHostName();
    }

    @Override
    public int getServerPort() {
        return exchange.getLocalAddress().getPort();
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
        return exchange.getRemoteAddress().getHostName();
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
        return "https".equalsIgnoreCase(exchange.getRequestURI().getScheme());
    }

    @Override
    public int getRemotePort() {
        return exchange.getRemoteAddress().getPort();
    }

    @Override
    public String getLocalName() {
        return exchange.getLocalAddress().getHostName();
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
