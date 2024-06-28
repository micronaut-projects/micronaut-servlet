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
package io.micronaut.http.poja.test;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.runtime.ApplicationConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

/**
 * An embedded server that uses {@link TestingServerlessApplication} as application.
 * It can be used for testing POJA serverless applications the same way a normal micronaut
 * server would be tested.
 *
 * <p>This class is required because the {@link TestingServerlessApplication} cannot
 * extend {@link EmbeddedServer} because of conflicting type arguments.</p>
 *
 * @author Andriy Dmytruk
 */
@Singleton
@Requires(env = Environment.TEST)
@Replaces(TestingServerlessApplication.class)
public record TestingServerlessEmbeddedServer(
    TestingServerlessApplication application
) implements EmbeddedServer {

    @Override
    public int getPort() {
        application.start();
        return application.getPort();
    }

    @Override
    public String getHost() {
        return "localhost";
    }

    @Override
    public String getScheme() {
        return "http";
    }

    @Override
    public URL getURL() {
        try {
            return getURI().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public URI getURI() {
        return URI.create("http://localhost:" + getPort());
    }

    @Override
    public ApplicationContext getApplicationContext() {
        return application.getApplicationContext();
    }

    @Override
    public ApplicationConfiguration getApplicationConfiguration() {
        return application.getApplicationConfiguration();
    }

    @Override
    public boolean isRunning() {
        return application.isRunning();
    }
}
