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
package io.micronaut.http.poja;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.SocketChannel;

/**
 * The addresses of the connection the requests arrive on, when the streams come from a socket: the server address
 * selects the routes of a port, and the remote address is the client as the server resolves it.
 *
 * @param localAddress  The address the server accepted the connection on, or {@code null} if unknown
 * @param remoteAddress The address of the client, or {@code null} if unknown
 * @since 6.2.0
 */
@Internal
public record PojaConnection(@Nullable InetSocketAddress localAddress, @Nullable InetSocketAddress remoteAddress) {

    /**
     * A connection whose addresses are not known, e.g. standard input and output.
     */
    public static final PojaConnection UNKNOWN = new PojaConnection(null, null);

    /**
     * @param socket The socket
     * @return The connection of the socket
     */
    public static PojaConnection of(Socket socket) {
        return new PojaConnection(inet(socket.getLocalSocketAddress()), inet(socket.getRemoteSocketAddress()));
    }

    /**
     * @param channel The channel, e.g. the inherited one
     * @return The connection of the channel, unknown unless it is a socket
     */
    public static PojaConnection of(@Nullable Channel channel) {
        if (channel instanceof SocketChannel socketChannel) {
            try {
                return new PojaConnection(inet(socketChannel.getLocalAddress()), inet(socketChannel.getRemoteAddress()));
            } catch (IOException e) {
                return UNKNOWN;
            }
        }
        return UNKNOWN;
    }

    private static @Nullable InetSocketAddress inet(@Nullable SocketAddress address) {
        return address instanceof InetSocketAddress inet ? inet : null;
    }
}
