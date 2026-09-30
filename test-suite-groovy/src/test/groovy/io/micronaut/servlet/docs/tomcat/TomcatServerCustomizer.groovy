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
class TomcatServerCustomizer implements BeanCreatedEventListener<Tomcat> {
    @Override
    Tomcat onCreated(BeanCreatedEvent<Tomcat> event) {
        Tomcat tomcat = event.bean
        // perform customizations, for example:
        tomcat.server.utilityThreads = 2
        return tomcat
    }
}
// end::class[]
