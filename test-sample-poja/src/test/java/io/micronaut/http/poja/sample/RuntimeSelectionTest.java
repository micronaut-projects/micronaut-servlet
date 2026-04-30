package io.micronaut.http.poja.sample;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.poja.apache.ApacheServerlessApplication;
import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.servlet.jetty.JettyServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RuntimeSelectionTest {
    private static final String JDK_ENABLED_PROPERTY = "micronaut.server.jdk.enabled";

    @Test
    void selectsPojaWhenJettyIsDisabled() {
        try (ApplicationContext context = ApplicationContext.builder()
            .deduceEnvironment(false)
            .properties(Map.of(
                "micronaut.server.jetty.enabled", Boolean.FALSE.toString(),
                JDK_ENABLED_PROPERTY, Boolean.FALSE.toString()
            ))
            .start()) {
            EmbeddedApplication<?> embeddedApplication = context.getBean(EmbeddedApplication.class);
            assertInstanceOf(ApacheServerlessApplication.class, embeddedApplication);
        }
    }

    @Test
    void selectsJettyWhenPojaIsDisabled() {
        try (ApplicationContext context = ApplicationContext.builder()
            .deduceEnvironment(false)
            .properties(Map.of(
                "poja.apache.enabled", Boolean.FALSE.toString(),
                "micronaut.server.jetty.enabled", Boolean.TRUE.toString(),
                JDK_ENABLED_PROPERTY, Boolean.FALSE.toString()
            ))
            .start()) {
            EmbeddedApplication<?> embeddedApplication = context.getBean(EmbeddedApplication.class);
            assertInstanceOf(JettyServer.class, embeddedApplication);
        }
    }
}
