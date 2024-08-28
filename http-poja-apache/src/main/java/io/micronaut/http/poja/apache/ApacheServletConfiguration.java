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
package io.micronaut.http.poja.apache;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

/**
 * Configuration specific to the Apache POJA serverless application.
 *
 * @param inputBufferSize The size of the buffer that is used to read and parse the HTTP request
 *                        (in bytes). Default value is 8192 (8Kb).
 * @param outputBufferSize The size of the buffer that is used to write the HTTP response
 *                        (in bytes). Default value is 8192 (8Kb).
 * @param useInheritedChannel When true, the inherited channel will be used by if present.
 *                            Otherwise, STDIN and STDOUT will be used.
 * @author Andriy Dmytruk
 * @since 4.10.0
 */
@ConfigurationProperties("poja.apache")
public record ApacheServletConfiguration(
    @Bindable(defaultValue = "8192")
    int inputBufferSize,
    @Bindable(defaultValue = "8192")
    int outputBufferSize,
    @Bindable(defaultValue = "true")
    boolean useInheritedChannel
) {

}
