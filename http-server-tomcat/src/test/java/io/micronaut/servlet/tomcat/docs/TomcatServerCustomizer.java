
package io.micronaut.servlet.tomcat.docs;

// tag::imports[]
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import org.apache.catalina.startup.Tomcat;
import jakarta.inject.Singleton;
// end::imports[]

// tag::class[]
@Singleton
public class TomcatServerCustomizer implements BeanCreatedEventListener<Tomcat> {
    @Override
    public Tomcat onCreated(BeanCreatedEvent<Tomcat> event) {
        Tomcat tomcat = event.getBean();
        // perform customizations...
        return tomcat;
    }
}
// end::class[]
