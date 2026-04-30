package io.micronaut.servlet.http.server.jdk;

import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkEnablementTest {

    @Test
    void jdkRuntimeCanBeDisabled() {
        try (ApplicationContext context = ApplicationContext.builder()
            .deduceEnvironment(false)
            .properties(Map.of(
                JdkHttpServerConfiguration.ENABLED_PROPERTY, Boolean.FALSE.toString()
            ))
            .start()) {
            assertTrue(context.findBean(HttpServer.class).isEmpty());
            assertTrue(context.findBean(EmbeddedServer.class).isEmpty());
        }
    }
}
