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

import io.micronaut.core.annotation.Internal;
import jakarta.websocket.Extension;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The default configurator of the Jakarta WebSocket API on the JDK server, which a
 * {@link ServerEndpointConfig.Configurator} delegates to for what it does not override. It is
 * found through the service loader, as a Jakarta container provides its own, and behaves as
 * the specification has it, so that it serves alike should it be found for another container.
 *
 * @author Denis Stepanov
 * @since 6.2.0
 */
@Internal
public final class JdkServerEndpointConfigurator extends ServerEndpointConfig.Configurator {

    @Override
    public String getNegotiatedSubprotocol(List<String> supported, List<String> requested) {
        for (String protocol : requested) {
            if (supported.contains(protocol)) {
                return protocol;
            }
        }
        return "";
    }

    @Override
    public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested) {
        Set<String> names = installed.stream().map(Extension::getName).collect(Collectors.toSet());
        return requested.stream().filter(extension -> names.contains(extension.getName())).toList();
    }

    @Override
    public boolean checkOrigin(String originHeaderValue) {
        return true;
    }

    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
        // nothing by default
    }

    @Override
    public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
        try {
            return endpointClass.getConstructor().newInstance();
        } catch (InvocationTargetException | NoSuchMethodException | IllegalAccessException e) {
            InstantiationException failure = new InstantiationException("Cannot create endpoint " + endpointClass.getName());
            failure.initCause(e);
            throw failure;
        }
    }
}
