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
package io.micronaut.servlet.http.server.jdk.websocket;

import com.sun.net.httpserver.HttpExchange;
import io.micronaut.core.annotation.Internal;

/**
 * The servlet request of the JDK server, whose exchange a WebSocket upgrade takes over.
 *
 * @author Denis Stepanov
 * @since 6.2.0
 */
@Internal
public interface UpgradableExchange {

    /**
     * @return The exchange of the request
     */
    HttpExchange getHttpExchange();

    /**
     * Hands the exchange, once switched to another protocol, to what runs that protocol. The
     * connection is run on the thread of the exchange once the dispatch of the request is over,
     * and the exchange is closed when it returns.
     *
     * @param connection Runs the connection until it ends
     */
    void upgraded(Runnable connection);
}
