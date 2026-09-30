package io.micronaut.servlet.docs.servletannotation;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the filter factory shown in the Servlet annotations section of the guide, so the
 * documented sources are real, compiled and covered.
 */
@Property(name = "spec.name", value = "MyFilterFactoryTest")
@Property(name = "my.filter.mapping", value = "/mapped-filter/*")
@MicronautTest
class MyFilterFactoryTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void theFilterRunsForItsMappings() {
        assertEquals("true", client.toBlocking().retrieve("/extra-filter/attribute"));
        assertEquals("true", client.toBlocking().retrieve("/mapped-filter/attribute"));
    }

    @Test
    void theFilterDoesNotRunForOtherPaths() {
        assertEquals("null", client.toBlocking().retrieve("/unfiltered/attribute"));
    }
}
