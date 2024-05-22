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
package io.micronaut.servlet.tomcat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.servlet.http.ServletConfiguration;
import jakarta.inject.Singleton;
import java.util.concurrent.ExecutorService;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.ProtocolHandler;
import org.apache.tomcat.util.threads.VirtualThreadExecutor;

/**
 * Enables virtual thread configuration if enabled.
 */
@Requires(sdk = Requires.Sdk.JAVA, version = "21")
@Singleton
public class TomcatVirtualThreadEnabler implements BeanCreatedEventListener<Connector> {
    private final ServletConfiguration servletConfiguration;
    private final ApplicationContext applicationContext;

    public TomcatVirtualThreadEnabler(ServletConfiguration servletConfiguration, ApplicationContext applicationContext) {
        this.servletConfiguration = servletConfiguration;
        this.applicationContext = applicationContext;
    }

    @Override
    public Connector onCreated(@NonNull BeanCreatedEvent<Connector> event) {
        Connector connector = event.getBean();
        if (servletConfiguration.isEnableVirtualThreads()) {
            ProtocolHandler protocolHandler = connector.getProtocolHandler();
            ExecutorService executorService = applicationContext.getBean(ExecutorService.class, Qualifiers.byName(TaskExecutors.BLOCKING));
            protocolHandler.setExecutor(executorService);
        }
        return connector;
    }
}
