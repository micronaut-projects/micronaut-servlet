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
package io.micronaut.servlet.undertow;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.DevelopmentActive;
import io.micronaut.core.annotation.Internal;
import io.micronaut.web.router.Router;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.util.TreeSet;

/**
 * Builds the Undertow server of a generation of the application in development mode, in place of
 * {@link UndertowFactory#undertowServer(Undertow.Builder)}: the server kept across restarts by
 * {@link RetainedUndertowServer}, with the generation's deployment staged behind its gate, or, when none is kept or the
 * generation listens otherwise, one built from the generation's builder with the gate as its root handler, kept from
 * then on.
 *
 * <p>Not a subclass of {@link UndertowFactory}: a subclass of a factory produces every bean of the factory again.</p>
 *
 * @author graemerocher
 * @since 6.3.0
 */
@Internal
@Factory
@Requires(beans = UndertowFactory.class)
@DevelopmentActive
final class DevelopmentUndertowFactory {

    /**
     * The Undertow server the generation runs.
     *
     * @param factory The factory, which built the generation's handler and deployment
     * @param builder The generation's builder
     * @param deploymentInfo The generation's deployment
     * @param router The router, whose exposed ports the server listens on
     * @param retained The server kept across restarts
     * @return The server, not started for the first generation
     */
    @Singleton
    @Primary
    @Replaces(bean = Undertow.class, factory = UndertowFactory.class)
    Undertow developmentUndertowServer(UndertowFactory factory,
                                       Undertow.Builder builder,
                                       DeploymentInfo deploymentInfo,
                                       @Nullable Router router,
                                       RetainedUndertowServer retained) {
        HttpHandler handler = factory.getRootHandler();
        if (handler == null) {
            throw new IllegalStateException("The Undertow factory built no handler");
        }
        // the deployment the builder just added to the static container, under the generation's name
        var manager = Servlets.defaultContainer().getDeployment(deploymentInfo.getDeploymentName());
        // the listeners are configured under the prefixes that release the kept server, but for the exposed ports
        String signature = "exposed " + (router == null ? "[]" : new TreeSet<>(router.getExposedPorts()).toString());
        return retained.serve(builder, new RetainedUndertowServer.Served(handler, manager), signature);
    }
}
