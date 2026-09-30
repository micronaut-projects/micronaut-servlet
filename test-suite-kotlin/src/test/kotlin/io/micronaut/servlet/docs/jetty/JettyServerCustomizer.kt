package io.micronaut.servlet.docs.jetty

// tag::imports[]
import io.micronaut.context.event.BeanCreatedEvent
import io.micronaut.context.event.BeanCreatedEventListener
import org.eclipse.jetty.server.Server
import jakarta.inject.Singleton
// end::imports[]
import io.micronaut.context.annotation.Requires

@Requires(property = "spec.name", value = "JettyServerCustomizerTest")
// tag::class[]
@Singleton
class JettyServerCustomizer : BeanCreatedEventListener<Server> {
    override fun onCreated(event: BeanCreatedEvent<Server>): Server {
        val jettyServer = event.bean
        // perform customizations, for example:
        jettyServer.stopTimeout = 5000
        return jettyServer
    }
}
// end::class[]
