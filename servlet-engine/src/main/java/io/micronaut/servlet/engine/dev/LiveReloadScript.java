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
package io.micronaut.servlet.engine.dev;

import io.micronaut.core.annotation.Internal;
import io.micronaut.dev.DevRuntime;
import io.micronaut.dev.livereload.LiveReloadServer;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * The LiveReload script tag of the development runtime and how it goes into an HTML page, shared by the servlet filter
 * and the containers' own static resources: the tag is {@link LiveReloadServer#scriptTag(int)}, as
 * {@code micronaut-dev}'s filter puts it in Micronaut's responses, and goes before the last closing body tag. The page
 * is searched as bytes, without being decoded, which holds for the ASCII-compatible charsets of HTML pages.
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
public final class LiveReloadScript {

    /**
     * How much of a page is held back at most: a larger one is sent as written, without the script.
     */
    public static final int MAX_BUFFERED = 8 * 1024 * 1024;

    private static final byte[] BODY_END = "</body>".getBytes(StandardCharsets.US_ASCII);

    private LiveReloadScript() {
    }

    /**
     * The tag the pages get, when the runtime runs a LiveReload server and the manifest does not turn the injection off.
     *
     * @param runtime The development runtime
     * @return The tag, in ASCII, or null
     */
    public static byte @Nullable [] tag(DevRuntime runtime) {
        LiveReloadServer server = runtime.liveReload().orElse(null);
        if (server == null || !runtime.manifest().liveReload().injectScript()) {
            return null;
        }
        return LiveReloadServer.scriptTag(server.port()).getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Whether a content type is HTML's.
     *
     * @param contentType The content type, if any
     * @return True for {@code text/html}, with any parameters
     */
    public static boolean isHtml(@Nullable String contentType) {
        if (contentType == null) {
            return false;
        }
        int parameters = contentType.indexOf(';');
        String type = (parameters < 0 ? contentType : contentType.substring(0, parameters)).trim();
        return "text/html".equalsIgnoreCase(type);
    }

    /**
     * Whether a content coding leaves the page as it is.
     *
     * @param contentEncoding The {@code Content-Encoding}, if any
     * @return True for none or {@code identity}
     */
    public static boolean isIdentity(@Nullable String contentEncoding) {
        return contentEncoding == null || contentEncoding.isBlank() || "identity".equalsIgnoreCase(contentEncoding.trim());
    }

    /**
     * Whether the page has its closing body tag yet.
     *
     * @param page The page so far
     * @param length How many of its bytes are written
     * @return True if the closing body tag is among them
     */
    public static boolean hasBodyEnd(byte[] page, int length) {
        return lastIndexOfIgnoreCase(page, length, BODY_END) >= 0;
    }

    /**
     * The page with the tag before its last closing body tag, found without regard to case.
     *
     * @param page The page
     * @param tag The tag
     * @return The page with the tag, or null if it has no closing body tag or carries the tag already
     */
    public static byte @Nullable [] inject(byte[] page, byte[] tag) {
        int end = lastIndexOfIgnoreCase(page, page.length, BODY_END);
        if (end < 0 || lastIndexOfIgnoreCase(page, page.length, tag) >= 0) {
            return null;
        }
        byte[] injected = new byte[page.length + tag.length];
        System.arraycopy(page, 0, injected, 0, end);
        System.arraycopy(tag, 0, injected, end, tag.length);
        System.arraycopy(page, end, injected, end + tag.length, page.length - end);
        return injected;
    }

    private static int lastIndexOfIgnoreCase(byte[] bytes, int length, byte[] target) {
        for (int i = length - target.length; i >= 0; i--) {
            boolean match = true;
            for (int j = 0; j < target.length; j++) {
                if (lower(bytes[i + j]) != lower(target[j])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static int lower(byte b) {
        return b >= 'A' && b <= 'Z' ? b + ('a' - 'A') : b;
    }
}
