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

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsExchange;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.StringUtils;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Writes an access log for the built-in server in the common log format, as the servlet containers and the Netty
 * server can. The line is written once the exchange has been handled, so it carries the status and the body length
 * that were actually sent.
 *
 * @since 6.2.0
 */
@Internal
@Singleton
@Requires(property = JdkHttpServerConfiguration.PREFIX + ".access-logger.enabled", value = StringUtils.TRUE)
final class AccessLogFilter extends Filter {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    private final Logger logger;
    private final List<Pattern> exclusions;

    AccessLogFilter(JdkHttpServerConfiguration configuration) {
        JdkHttpServerConfiguration.AccessLogger accessLogger = configuration.getAccessLogger();
        this.logger = LoggerFactory.getLogger(accessLogger.getLoggerName());
        this.exclusions = accessLogger.getExclusions().stream().map(Pattern::compile).toList();
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        ZonedDateTime received = ZonedDateTime.now();
        try {
            chain.doFilter(exchange);
        } finally {
            if (logger.isInfoEnabled() && !excluded(exchange)) {
                logger.info(line(exchange, received));
            }
        }
    }

    @Override
    public String description() {
        return "Micronaut access log";
    }

    private boolean excluded(HttpExchange exchange) {
        if (exclusions.isEmpty()) {
            return false;
        }
        String path = exchange.getRequestURI().getPath();
        return exclusions.stream().anyMatch(pattern -> pattern.matcher(path).matches());
    }

    /**
     * Formats the common log format line: remote host, identity, user, time, request line, status and bytes.
     */
    private static String line(HttpExchange exchange, ZonedDateTime received) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        String host = remote == null ? "-" : remote.getAddress().getHostAddress();
        String user = exchange.getPrincipal() == null ? "-" : exchange.getPrincipal().getUsername();
        String protocol = exchange instanceof HttpsExchange ? "HTTPS" : exchange.getProtocol();
        int status = exchange.getResponseCode();
        String length = exchange.getResponseHeaders().getFirst("Content-Length");
        return host + " - " + user + " [" + TIMESTAMP.format(received) + "] \""
            + exchange.getRequestMethod() + ' ' + exchange.getRequestURI() + ' ' + protocol + "\" "
            + (status < 0 ? "-" : String.valueOf(status)) + ' ' + (length == null ? "-" : length);
    }
}
