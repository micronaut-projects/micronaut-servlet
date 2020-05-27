/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.servlet.jetty.docs;

// tag::imports[]
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import org.eclipse.jetty.server.Server;
import javax.inject.Singleton;
// end::imports[]

// tag::class[]
@Singleton
public class JettyServerCustomizer implements BeanCreatedEventListener<Server> {
    @Override
    public Server onCreated(BeanCreatedEvent<Server> event) {
        Server jettyServer = event.getBean();
        // perform customizations...
        return jettyServer;
    }
}
// end::class[]
