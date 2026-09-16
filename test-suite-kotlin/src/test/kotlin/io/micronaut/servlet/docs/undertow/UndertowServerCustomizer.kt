package io.micronaut.servlet.docs.undertow

// tag::imports[]
import io.micronaut.context.event.BeanCreatedEvent
import io.micronaut.context.event.BeanCreatedEventListener
import io.undertow.Undertow
import jakarta.inject.Singleton
// end::imports[]
import io.micronaut.context.annotation.Requires

@Requires(property = "spec.name", value = "UndertowServerCustomizerTest")
// tag::class[]
@Singleton
class UndertowServerCustomizer : BeanCreatedEventListener<Undertow.Builder> {
    override fun onCreated(event: BeanCreatedEvent<Undertow.Builder>): Undertow.Builder {
        val undertowBuilder = event.bean
        // perform customizations, for example:
        undertowBuilder.setIoThreads(2)
        return undertowBuilder
    }
}
// end::class[]
