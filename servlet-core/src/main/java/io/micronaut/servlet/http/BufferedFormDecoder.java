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
     * Decodes a form without a limit on its fields or files.
     *
     * @param contentType The content type of the form
     * @param bytes       Its bytes
     * @param charset     The charset of a URL-encoded form
     * @return Its fields, in order
     */
    public static List<Field> decode(MediaType contentType, byte[] bytes, Charset charset) {
        return decode(contentType, bytes, charset, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    /**
     * @param contentType The content type of the form
     * @param bytes       Its bytes
     * @param charset     The charset of a URL-encoded form
     * @param maxFields   The most fields the form may have, {@code micronaut.http.forms.max-decoded-key-value-parameters}:
     *                    a form with more is refused with 413
     * @param maxFileSize The most bytes a file of a multipart form may have, {@code micronaut.server.multipart.max-file-size}:
     *                    a larger file is refused with 413
     * @return Its fields, in order
     */
    public static List<Field> decode(MediaType contentType, byte[] bytes, Charset charset, int maxFields, long maxFileSize) {
        if (contentType.matches(MediaType.MULTIPART_FORM_DATA_TYPE)) {
            String boundary = contentType.getParameters().get("boundary").orElse(null);
            if (boundary == null || boundary.isEmpty()) {
                throw badRequest("The multipart form has no boundary");
            }
            return multipart(bytes, unquote(boundary), maxFields, maxFileSize);
        }
        return urlEncoded(bytes, charset, maxFields);
    }

    private static List<Field> urlEncoded(byte[] bytes, Charset charset, int maxFields) {
        List<Field> fields = new ArrayList<>();
        for (String pair : new String(bytes, charset).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            if (fields.size() >= maxFields) {
                throw tooManyFields(maxFields);
            }
            int equals = pair.indexOf('=');
            String name = decodeComponent(equals == -1 ? pair : pair.substring(0, equals), charset);
            String value = equals == -1 ? "" : decodeComponent(pair.substring(equals + 1), charset);
            fields.add(new Field(new FormFieldMetadata(name, null, null), value.getBytes(charset)));
        }
        return fields;
    }

    /**
     * Decodes a name or value of a URL-encoded form.
     *
     * @param component The encoded name or value
     * @param charset   The charset of the form
     * @return The decoded name or value
     * @throws HttpStatusException 400 for a malformed percent-escape, e.g. {@code %GG}
     */
    public static String decodeComponent(String component, Charset charset) {
        try {
            return URLDecoder.decode(component, charset);
        } catch (IllegalArgumentException e) {
            throw badRequest("The form has a malformed escape: " + e.getMessage());
        }
    }

    /**
     * @param maxFields The limit of the fields of a form
     * @return The 413 that refuses a form with more fields
     */
    public static HttpStatusException tooManyFields(int maxFields) {
        return new HttpStatusException(HttpStatus.REQUEST_ENTITY_TOO_LARGE, "The form has more than " + maxFields + " fields");
    }

    private static List<Field> multipart(byte[] bytes, String boundary, int maxFields, long maxFileSize) {
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        List<Field> fields = new ArrayList<>();
        int start = firstDelimiter(bytes, delimiter);
        if (start < 0) {
            throw badRequest("The multipart form has no part");
        }
        int position = start + delimiter.length;
        while (true) {
            if (startsWith(bytes, position, "--")) {
                // the close delimiter
                return fields;
            }
            if (fields.size() >= maxFields) {
                throw tooManyFields(maxFields);
            }
            position = skipLineEnd(bytes, position);
            int headersEnd = indexOf(bytes, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), position);
            if (headersEnd < 0) {
                throw badRequest("A part of the multipart form has no end of its headers");
            }
            String headers = new String(bytes, position, headersEnd - position, StandardCharsets.UTF_8);
            int contentStart = headersEnd + 4;
            int next = nextDelimiter(bytes, delimiter, contentStart);
            if (next < 0) {
                throw badRequest("The multipart form has no close delimiter");
            }
            // the CRLF before the delimiter belongs to it
            int contentEnd = next - 2;
            FormFieldMetadata metadata = metadata(headers);
            if (metadata.fileName() != null && contentEnd - contentStart > maxFileSize) {
                throw new HttpStatusException(HttpStatus.REQUEST_ENTITY_TOO_LARGE,
                    "The file [" + metadata.name() + "] of the multipart form exceeds the limit of " + maxFileSize + " bytes");
            }
            fields.add(new Field(metadata, Arrays.copyOfRange(bytes, contentStart, contentEnd)));
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

    /**
     * The first delimiter: at the start of the body, or on a line of its own after a preamble.
     */
    private static int firstDelimiter(byte[] bytes, byte[] delimiter) {
        if (startsWith(bytes, 0, delimiter) && endsDelimiter(bytes, delimiter.length)) {
            return 0;
        }
        return nextDelimiter(bytes, delimiter, 0);
    }

    /**
     * The next delimiter in the content of a part: the boundary at the start of a line, the CRLF before it belonging
     * to it, followed by the dashes of the close delimiter or by a line end after any transport padding
     * (RFC 2046 5.1.1). Bytes that only look like the delimiter, such as {@code --boundaryX}, or
     * {@code --boundary--} in the middle of a line, are content.
     */
    private static int nextDelimiter(byte[] bytes, byte[] delimiter, int from) {
        int next = indexOf(bytes, delimiter, from);
        while (next >= 0 && !(next >= from + 2 && bytes[next - 2] == '\r' && bytes[next - 1] == '\n'
            && endsDelimiter(bytes, next + delimiter.length))) {
            next = indexOf(bytes, delimiter, next + 1);
        }
        return next;
    }

    private static boolean endsDelimiter(byte[] bytes, int position) {
        int p = position;
        boolean close = startsWith(bytes, p, "--");
        if (close) {
            p += 2;
        }
        while (p < bytes.length && (bytes[p] == ' ' || bytes[p] == '\t')) {
            p++;
        }
        if (p + 1 < bytes.length && bytes[p] == '\r' && bytes[p + 1] == '\n') {
            return true;
        }
        // the close delimiter may end the body without a line end
        return close && p == bytes.length;
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

    private static boolean startsWith(byte[] bytes, int position, byte[] prefix) {
        if (position + prefix.length > bytes.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[position + i] != prefix[i]) {
                return false;
            }
        }
        return true;
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

        @Override
        public boolean equals(Object o) {
            return o instanceof Field other && metadata.equals(other.metadata) && Arrays.equals(content, other.content);
        }

        @Override
        public int hashCode() {
            return 31 * metadata.hashCode() + Arrays.hashCode(content);
        }

        @Override
        public String toString() {
            return "Field[metadata=" + metadata + ", content=" + content.length + " bytes]";
        }
    }
}
