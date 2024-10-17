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
package io.micronaut.http.poja.exception;

/**
 * An exception to be thrown when no additional requests can be parsed.
 * This can happen if the input stream is closed when waiting for a new request.
 * This is not an error condition, therefore application can simply stop.
 */
public class NoPojaRequestException extends RuntimeException {

    /**
     * Constructor for the exception.
     */
    public NoPojaRequestException() {
        super("No new request was found in the input stream");
    }

}
