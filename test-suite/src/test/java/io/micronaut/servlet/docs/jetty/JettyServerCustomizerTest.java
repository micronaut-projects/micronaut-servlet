package io.micronaut.servlet.docs.jetty;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the listener shown in the Jetty section of the guide, so the documented sources
 * are real, compiled and covered.
 */
@Property(name = "spec.name", value = "JettyServerCustomizerTest")
@MicronautTest
class JettyServerCustomizerTest {

    @Inject
    Server server;

    @Test
    void theListenerCustomizesTheJettyServer() {
        assertEquals(5000, server.getStopTimeout());
    }
}
