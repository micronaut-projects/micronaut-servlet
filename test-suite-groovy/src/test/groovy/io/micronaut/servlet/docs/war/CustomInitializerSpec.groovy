package io.micronaut.servlet.docs.war

import io.micronaut.context.ApplicationContext
import io.micronaut.servlet.engine.DefaultMicronautServlet
import jakarta.servlet.FilterRegistration
import jakarta.servlet.ServletContext
import jakarta.servlet.ServletRegistration
import spock.lang.Specification

import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Exercises the initializer shown in the WAR deployment section of the guide, so the documented
 * sources are real, compiled and covered. The container is stood in for by a {@link ServletContext}
 * proxy, and the configuration under {@code classpath:/some/path} is found through the
 * overridden locations.
 */
class CustomInitializerSpec extends Specification {

    void "the initializer reads configuration from the overridden locations"() {
        given:
        ServletContext servletContext = servletContext()

        when:
        new CustomInitializer().onStartup([] as Set, servletContext)
        ApplicationContext applicationContext = (ApplicationContext) servletContext.getAttribute(DefaultMicronautServlet.CONTEXT_ATTRIBUTE)

        then:
        applicationContext != null
        applicationContext.getProperty("custom.initializer.location", String).orElse(null) == "classpath:/some/path"

        cleanup:
        applicationContext?.close()
    }

    private static ServletContext servletContext() {
        Map<String, Object> attributes = new ConcurrentHashMap<>()
        (ServletContext) Proxy.newProxyInstance(
            CustomInitializerSpec.classLoader,
            [ServletContext] as Class[],
            { proxy, method, args ->
                switch (method.name) {
                    case "addFilter": return registration(FilterRegistration.Dynamic)
                    case "addServlet": return registration(ServletRegistration.Dynamic)
                    case "getAttribute": return attributes.get(args[0])
                    case "getAttributeNames": return Collections.enumeration(attributes.keySet())
                    case "getClassLoader": return CustomInitializerSpec.classLoader
                    case "getContextPath": return ""
                    case "setAttribute":
                        attributes.put((String) args[0], args[1])
                        return null
                    default: return defaultValue(method.returnType)
                }
            }
        )
    }

    private static <T> T registration(Class<T> type) {
        type.cast(Proxy.newProxyInstance(
            CustomInitializerSpec.classLoader,
            [type] as Class[],
            { proxy, method, args -> defaultValue(method.returnType) }
        ))
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Boolean.TYPE) {
            return false
        }
        if (returnType == Integer.TYPE) {
            return 0
        }
        if (Set.isAssignableFrom(returnType)) {
            return Collections.emptySet()
        }
        return null
    }
}
