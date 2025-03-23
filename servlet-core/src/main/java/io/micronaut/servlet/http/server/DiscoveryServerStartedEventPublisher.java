/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.servlet.http.server;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;
import io.micronaut.discovery.ServiceInstance;
import io.micronaut.discovery.event.ServiceReadyEvent;

/**
 * Publishes a {@link ServiceReadyEvent} when the server starts if there is an {@link ApplicationConfiguration#getName()} set.
 */
@Requires(classes = { ServiceInstance.class, ServiceReadyEvent.class })
@Singleton
public class DiscoveryServerStartedEventPublisher implements ApplicationEventListener<ServerStartupEvent> {
    private final ApplicationConfiguration applicationConfiguration;
    private final EmbeddedServer embeddedServer;
    private final ApplicationEventPublisher<ServiceReadyEvent> applicationEventPublisher;

    public DiscoveryServerStartedEventPublisher(ApplicationConfiguration applicationConfiguration,
                                                EmbeddedServer embeddedServer,
                                                ApplicationEventPublisher<ServiceReadyEvent> applicationEventPublisher) {
        this.applicationConfiguration = applicationConfiguration;
        this.embeddedServer = embeddedServer;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        applicationConfiguration.getName().ifPresent((name) -> {
            ServiceInstance.Builder builder = ServiceInstance.builder(name, embeddedServer.getURI());
            ApplicationConfiguration.InstanceConfiguration instance = applicationConfiguration.getInstance();
            instance.getGroup().ifPresent(builder::group);
            instance.getZone().ifPresent(builder::zone);
            builder.metadata(instance.getMetadata());
            instance.getId().ifPresent(builder::instanceId);
            applicationEventPublisher.publishEvent(new ServiceReadyEvent(builder.build()));
        });
    }
}
