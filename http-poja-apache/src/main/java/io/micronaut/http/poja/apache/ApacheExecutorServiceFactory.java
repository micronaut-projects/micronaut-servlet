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
package io.micronaut.http.poja.apache;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.util.StringUtils;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.executor.ExecutorConfiguration;
import io.micronaut.scheduling.executor.IOExecutorServiceConfig;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.util.concurrent.ExecutorService;

/**
 * Configures POJA to keep blocking work on the request thread by default.
 */
@Factory
@Requires(property = ApacheRuntimeConfiguration.ENABLED_PROPERTY, value = StringUtils.TRUE, defaultValue = StringUtils.TRUE)
final class ApacheExecutorServiceFactory {

    @Singleton
    @Named(TaskExecutors.BLOCKING)
    @Replaces(value = ExecutorService.class, factory = IOExecutorServiceConfig.class, named = TaskExecutors.BLOCKING)
    @Requires(missingProperty = ExecutorConfiguration.PREFIX + "." + TaskExecutors.BLOCKING + ".type")
    ExecutorService blocking() {
        return new CurrentThreadExecutorService();
    }
}
