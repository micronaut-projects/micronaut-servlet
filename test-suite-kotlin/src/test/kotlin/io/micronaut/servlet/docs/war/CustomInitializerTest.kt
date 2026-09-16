package io.micronaut.servlet.docs.war

import io.kotest.matchers.shouldBe
import io.micronaut.context.ApplicationContext
import io.micronaut.servlet.engine.DefaultMicronautServlet
import jakarta.servlet.FilterRegistration
import jakarta.servlet.ServletContext
import jakarta.servlet.ServletRegistration
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Exercises the initializer shown in the WAR deployment section of the guide, so the documented
 * Kotlin sources are real, compiled and covered. The container is stood in for by a
 * [ServletContext] proxy, and the configuration under `classpath:/some/path` is found through
 * the overridden locations.
 */
class CustomInitializerTest {

    @Test
    fun theInitializerReadsConfigurationFromTheOverriddenLocations() {
        val servletContext = servletContext()

        CustomInitializer().onStartup(emptySet(), servletContext)

        val applicationContext = servletContext.getAttribute(DefaultMicronautServlet.CONTEXT_ATTRIBUTE) as ApplicationContext
        applicationContext.use {
            it.getProperty("custom.initializer.location", String::class.java).orElse(null) shouldBe "classpath:/some/path"
        }
    }

    private fun servletContext(): ServletContext {
        val attributes = ConcurrentHashMap<String, Any>()
        return Proxy.newProxyInstance(
            CustomInitializerTest::class.java.classLoader,
            arrayOf(ServletContext::class.java)
        ) { _, method, args ->
            when (method.name) {
                "addFilter" -> registration(FilterRegistration.Dynamic::class.java)
                "addServlet" -> registration(ServletRegistration.Dynamic::class.java)
                "getAttribute" -> attributes[args[0]]
                "getAttributeNames" -> Collections.enumeration(attributes.keys)
                "getClassLoader" -> CustomInitializerTest::class.java.classLoader
                "getContextPath" -> ""
                "setAttribute" -> {
                    attributes[args[0] as String] = args[1]
                    null
                }
                else -> defaultValue(method.returnType)
            }
        } as ServletContext
    }

    private fun <T> registration(type: Class<T>): T = type.cast(
        Proxy.newProxyInstance(CustomInitializerTest::class.java.classLoader, arrayOf(type)) { _, method, _ ->
            defaultValue(method.returnType)
        }
    )

    private fun defaultValue(returnType: Class<*>): Any? = when {
        returnType == java.lang.Boolean.TYPE -> false
        returnType == Integer.TYPE -> 0
        Set::class.java.isAssignableFrom(returnType) -> emptySet<Any>()
        else -> null
    }
}
