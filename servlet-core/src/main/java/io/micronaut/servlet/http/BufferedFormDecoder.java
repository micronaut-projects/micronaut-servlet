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
package io.micronaut.servlet.http;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.MediaType;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.multipart.FormFieldMetadata;
import org.jspecify.annotations.Nullable;

import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Decodes the fields of a form from bytes that are already buffered, e.g. a copy of the body of a
 * request that a filter reads while the route reads the body itself: the container only parses
 * the form it received, from its own stream, so a copy is decoded here. A multipart form is split
 * at its boundary as RFC 7578 describes, a URL-encoded form at its {@code &}.
 *
 * @author Denis Stepanov
 * @since 6.3.0
 */
@Internal
public final class BufferedFormDecoder {

    private BufferedFormDecoder() {
    }


    /**
     * @param contentType The content type of the form
     * @param bytes       Its bytes
     * @param charset     The charset of a URL-encoded form
     * @return Its fields, in order
     */
    public static List<Field> decode(MediaType contentType, byte[] bytes, Charset charset) {
        if (contentType.matches(MediaType.MULTIPART_FORM_DATA_TYPE)) {
            String boundary = contentType.getParameters().get("boundary").orElse(null);
            if (boundary == null || boundary.isEmpty()) {
                throw badRequest("The multipart form has no boundary");
            }
            return multipart(bytes, unquote(boundary));
        }
        return urlEncoded(bytes, charset);
    }

    private static List<Field> urlEncoded(byte[] bytes, Charset charset) {
        List<Field> fields = new ArrayList<>();
        for (String pair : new String(bytes, charset).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = URLDecoder.decode(equals == -1 ? pair : pair.substring(0, equals), charset);
            String value = equals == -1 ? "" : URLDecoder.decode(pair.substring(equals + 1), charset);
            fields.add(new Field(new FormFieldMetadata(name, null, null), value.getBytes(charset)));
        }
        return fields;
    }

    private static List<Field> multipart(byte[] bytes, String boundary) {
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        List<Field> fields = new ArrayList<>();
        int start = indexOf(bytes, delimiter, 0);
        if (start < 0) {
            throw badRequest("The multipart form has no part");
        }
        int position = start + delimiter.length;
        while (true) {
            if (startsWith(bytes, position, "--")) {
                // the close delimiter
                return fields;
            }
            position = skipLineEnd(bytes, position);
            int headersEnd = indexOf(bytes, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), position);
            if (headersEnd < 0) {
                throw badRequest("A part of the multipart form has no end of its headers");
            }
            String headers = new String(bytes, position, headersEnd - position, StandardCharsets.UTF_8);
            int contentStart = headersEnd + 4;
            int next = indexOf(bytes, delimiter, contentStart);
            if (next < 0) {
                throw badRequest("The multipart form has no close delimiter");
            }
            // the CRLF before the delimiter belongs to it
            int contentEnd = next >= 2 && bytes[next - 2] == '\r' && bytes[next - 1] == '\n' ? next - 2 : next;
            fields.add(new Field(metadata(headers), Arrays.copyOfRange(bytes, contentStart, contentEnd)));
            position = next + delimiter.length;
        }
    }

    private static FormFieldMetadata metadata(String headers) {
        String name = null;
        String fileName = null;
        MediaType contentType = null;
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String header = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (header.equals("content-disposition")) {
                for (String parameter : value.split(";")) {
                    int equals = parameter.indexOf('=');
                    if (equals < 0) {
                        continue;
                    }
                    String key = parameter.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                    String parameterValue = unquote(parameter.substring(equals + 1).trim());
                    if (key.equals("name")) {
                        name = parameterValue;
                    } else if (key.equals("filename")) {
                        fileName = parameterValue;
                    }
                }
            } else if (header.equals("content-type")) {
                contentType = MediaType.of(value);
            }
        }
        if (name == null) {
            throw badRequest("A part of the multipart form has no name");
        }
        return new FormFieldMetadata(name, fileName, contentType);
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }

    private static int skipLineEnd(byte[] bytes, int position) {
        // transport padding, then CRLF
        int p = position;
        while (p < bytes.length && (bytes[p] == ' ' || bytes[p] == '\t')) {
            p++;
        }
        if (p + 1 < bytes.length && bytes[p] == '\r' && bytes[p + 1] == '\n') {
            return p + 2;
        }
        throw badRequest("A delimiter of the multipart form is not followed by a line end");
    }

    private static boolean startsWith(byte[] bytes, int position, String prefix) {
        if (position + prefix.length() > bytes.length) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (bytes[position + i] != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] bytes, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= bytes.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (bytes[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static HttpStatusException badRequest(@Nullable String message) {
        return new HttpStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * A field of the form.
     *
     * @param metadata The name, file name and content type of the field
     * @param content  Its bytes
     */
    public record Field(FormFieldMetadata metadata, byte[] content) {
    }
}
