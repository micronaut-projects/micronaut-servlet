package io.micronaut.servlet.docs.tomcat

// tag::imports[]
import io.micronaut.context.event.BeanCreatedEvent
import io.micronaut.context.event.BeanCreatedEventListener
import org.apache.catalina.startup.Tomcat
import jakarta.inject.Singleton
// end::imports[]
import io.micronaut.context.annotation.Requires

@Requires(property = "spec.name", value = "TomcatServerCustomizerTest")
// tag::class[]
@Singleton
class TomcatServerCustomizer : BeanCreatedEventListener<Tomcat> {
    override fun onCreated(event: BeanCreatedEvent<Tomcat>): Tomcat {
        val tomcat = event.bean
        // perform customizations, for example:
        tomcat.server.utilityThreads = 2
        return tomcat
    }
}
// end::class[]
