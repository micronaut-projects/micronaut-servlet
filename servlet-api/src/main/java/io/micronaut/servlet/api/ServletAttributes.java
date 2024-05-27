/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.servlet.api;

import io.micronaut.core.annotation.NonNull;

/**
 * Attributes to lookup servlet related types from the request.
 *
 * @see io.micronaut.http.HttpAttributes
 */
public enum ServletAttributes implements CharSequence {
    /**
     * Attribute to lookup the {@link jakarta.servlet.ServletConfig}.
     */
    SERVLET_CONFIG("io.micronaut.servlet.SERVLET_CONFIG"),

    /**
     * Attribute to lookup the {@link jakarta.servlet.ServletContext}.
     */
    SERVLET_CONTEXT("io.micronaut.servlet.SERVLET_CONTEXT");

    private final String name;

    ServletAttributes(String name) {
        this.name = name;
    }

    @Override
    public int length() {
        return name.length();
    }

    @Override
    public char charAt(int index) {
        return name.charAt(index);
    }

    @Override
    @NonNull
    public CharSequence subSequence(int start, int end) {
        return name.substring(start, end);
    }


    @Override
    @NonNull
    public String toString() {
        return name;
    }
}
