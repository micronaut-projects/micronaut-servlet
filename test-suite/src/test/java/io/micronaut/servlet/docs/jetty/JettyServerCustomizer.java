package io.micronaut.servlet.docs.jetty;

// tag::imports[]
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import org.eclipse.jetty.server.Server;
import jakarta.inject.Singleton;
// end::imports[]
import io.micronaut.context.annotation.Requires;

@Requires(property = "spec.name", value = "JettyServerCustomizerTest")
// tag::class[]
@Singleton
public class JettyServerCustomizer implements BeanCreatedEventListener<Server> {
    @Override
    public Server onCreated(BeanCreatedEvent<Server> event) {
        Server jettyServer = event.getBean();
        // perform customizations, for example:
        jettyServer.setStopTimeout(5000);
        return jettyServer;
    }
}
// end::class[]
