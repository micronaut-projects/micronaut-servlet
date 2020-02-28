package io.micronaut.servlet.undertow.docs;

// tag::imports[]
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.undertow.Undertow;
import javax.inject.Singleton;
// end::imports[]

// tag::class[]
@Singleton
public class UndertowServerCustomizer implements BeanCreatedEventListener<Undertow.Builder> {
    @Override
    public Undertow.Builder onCreated(BeanCreatedEvent<Undertow.Builder> event) {
        Undertow.Builder undertowBuilder = event.getBean();
        // perform customizations...
        return undertowBuilder;
    }
}
// end::class[]

