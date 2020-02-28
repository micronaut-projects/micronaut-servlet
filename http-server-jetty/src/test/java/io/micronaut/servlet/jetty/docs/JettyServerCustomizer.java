package io.micronaut.servlet.jetty.docs;

// tag::imports[]
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import org.eclipse.jetty.server.Server;
import javax.inject.Singleton;
// end::imports[]

// tag::class[]
@Singleton
public class JettyServerCustomizer implements BeanCreatedEventListener<Server> {
    @Override
    public Server onCreated(BeanCreatedEvent<Server> event) {
        Server jettyServer = event.getBean();
        // perform customizations...
        return jettyServer;
    }
}
// end::class[]
