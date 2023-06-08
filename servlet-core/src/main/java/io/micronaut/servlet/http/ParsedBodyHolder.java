/*
 * Copyright 2017-2023 original authors
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

/**
 * Interface to allow setting of parsed body on a request.
 * When we parse the body of a request using ServletBodyBinder we read the body via the inputStream.
 * Further requests to getBody (for example in an OnError handler) will then return an empty object as the stream is exhausted.
 * This allows us to effectively cache the body, so we get the same object in the later handler.
 *
 * @param <B> The type of the body
 */
public interface ParsedBodyHolder<B> {

    /**
     * Set the parsed body.
     * @param body the parsed body
     */
    void setParsedBody(B body);
}
