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
package io.micronaut.servlet.undertow;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.server.exceptions.InternalServerException;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.servlet.engine.server.AbstractServletServer;
import io.undertow.Undertow;

import jakarta.inject.Singleton;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of {@link AbstractServletServer} for Undertow.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Singleton
public class UndertowServer extends AbstractServletServer<Undertow> {

    private Map<String, Undertow.ListenerInfo> listenersByProtocol;

    /**
     * Default constructor.
     * @param applicationContext The app context
     * @param applicationConfiguration The app config
     * @param undertow The undertow instance
     */
    public UndertowServer(
            ApplicationContext applicationContext,
            ApplicationConfiguration applicationConfiguration,
            Undertow undertow) {
        super(applicationContext, applicationConfiguration, undertow);
    }

    @Override
    protected void startServer() throws Exception {
        Undertow server = getServer();
        server.start();
        this.listenersByProtocol = new HashMap<>();
        for (Undertow.ListenerInfo listenerInfo : server.getListenerInfo()) {
            if (!listenersByProtocol.containsKey(listenerInfo.getProtcol())) {
                listenersByProtocol.put(listenerInfo.getProtcol(), listenerInfo);
            }
        }
    }

    @Override
    protected void stopServer() throws Exception {
        getServer().stop();
    }

    @Override
    public int getPort() {
        Undertow.ListenerInfo https = listenersByProtocol.get("https");
        if (https != null) {
            return ((InetSocketAddress) https.getAddress()).getPort();
        }
        Undertow.ListenerInfo http = listenersByProtocol.get("http");
        if (http != null) {
            return ((InetSocketAddress) http.getAddress()).getPort();
        }
        return -1;
    }

    @Override
    public String getHost() {
        Undertow.ListenerInfo https = listenersByProtocol.get("https");
        if (https != null) {
            return ((InetSocketAddress) https.getAddress()).getHostName();
        }
        Undertow.ListenerInfo http = listenersByProtocol.get("http");
        if (http != null) {
            return ((InetSocketAddress) http.getAddress()).getHostName();
        }
        return "localhost";
    }

    @Override
    public String getScheme() {
        Undertow.ListenerInfo https = listenersByProtocol.get("https");
        if (https != null) {
            return https.getProtcol();
        }
        return "http";
    }

    @Override
    public URL getURL() {
        try {
            return getURI().toURL();
        } catch (MalformedURLException e) {
            throw new InternalServerException(e.getMessage(), e);
        }
    }

    @Override
    public URI getURI() {
        try {
            return new URI(getScheme(), null, getHost(), getPort(), null, null, null);
        } catch (URISyntaxException e) {
            throw new InternalServerException(e.getMessage(), e);
        }
    }

    @Override
    public boolean isRunning() {
        return getServer().getXnio() != null;
    }
}
