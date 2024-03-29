package io.micronaut.servlet.undertow

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.runtime.server.event.ServerShutdownEvent
import spock.lang.Specification

import jakarta.inject.Singleton
import java.util.concurrent.atomic.AtomicInteger

class UndertowShutdownSpec extends Specification {

    void "test the shutdown event is emitted exactly once"() {
        given:
        EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, ['spec.name': 'UndertowShutdownSpec'])
        ShutdownListener listener = embeddedServer.applicationContext.getBean(ShutdownListener)

        when:
        embeddedServer.stop()

        then:
        ShutdownListener.count.get() == 1
    }

    @Requires(property = 'spec.name', value = 'UndertowShutdownSpec')
    @Singleton
    static class ShutdownListener implements ApplicationEventListener<ServerShutdownEvent> {

        static AtomicInteger count = new AtomicInteger()

        @Override
        void onApplicationEvent(ServerShutdownEvent event) {
            count.incrementAndGet()
        }
    }
}
