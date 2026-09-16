package io.micronaut.servlet.docs.war;

import io.micronaut.context.ApplicationContext;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exercises the initializer shown in the WAR deployment section of the guide, so the documented
 * sources are real, compiled and covered. The container is stood in for by a {@link ServletContext}
 * proxy, and the configuration under {@code classpath:/some/path} is found through the
 * overridden locations.
 */
class CustomInitializerTest {

    @Test
    void theInitializerReadsConfigurationFromTheOverriddenLocations() {
        ServletContext servletContext = servletContext();

        new CustomInitializer().onStartup(Set.of(), servletContext);

        ApplicationContext applicationContext = (ApplicationContext) servletContext.getAttribute(DefaultMicronautServlet.CONTEXT_ATTRIBUTE);
        assertNotNull(applicationContext);
        try (applicationContext) {
            assertEquals("classpath:/some/path", applicationContext.getProperty("custom.initializer.location", String.class).orElse(null));
        }
    }

    private static ServletContext servletContext() {
        Map<String, Object> attributes = new ConcurrentHashMap<>();
        return (ServletContext) Proxy.newProxyInstance(
            CustomInitializerTest.class.getClassLoader(),
            new Class<?>[]{ServletContext.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "addFilter" -> registration(FilterRegistration.Dynamic.class);
                case "addServlet" -> registration(ServletRegistration.Dynamic.class);
                case "getAttribute" -> attributes.get(args[0]);
                case "getAttributeNames" -> Collections.enumeration(attributes.keySet());
                case "getClassLoader" -> CustomInitializerTest.class.getClassLoader();
                case "getContextPath" -> "";
                case "setAttribute" -> {
                    attributes.put((String) args[0], args[1]);
                    yield null;
                }
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static <T> T registration(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(
            CustomInitializerTest.class.getClassLoader(),
            new Class<?>[]{type},
            (proxy, method, args) -> defaultValue(method.getReturnType())
        ));
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (Set.class.isAssignableFrom(returnType)) {
            return Collections.emptySet();
        }
        return null;
    }
}
