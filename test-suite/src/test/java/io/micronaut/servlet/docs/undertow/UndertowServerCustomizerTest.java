package io.micronaut.servlet.docs.undertow;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.undertow.Undertow;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the listener shown in the Undertow section of the guide, so the documented sources
 * are real, compiled and covered. The test suite runs Jetty by default, so Undertow is enabled
 * for this test only.
 */
@Property(name = "spec.name", value = "UndertowServerCustomizerTest")
@Property(name = "micronaut.server.jetty.enabled", value = "false")
@Property(name = "micronaut.server.undertow.enabled", value = "true")
@MicronautTest
class UndertowServerCustomizerTest {

    @Inject
    Undertow undertow;

    @Test
    void theListenerCustomizesTheUndertowServer() {
        assertEquals(2, undertow.getWorker().getIoThreadCount());
    }
}
