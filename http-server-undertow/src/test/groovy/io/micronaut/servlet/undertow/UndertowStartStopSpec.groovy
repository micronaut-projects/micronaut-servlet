
package io.micronaut.servlet.undertow

import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.EmbeddedApplication
import spock.lang.Specification

class UndertowStartStopSpec extends Specification {

    void "test the bean context is not used after shutdown"() {
        when:
            def ctx = ApplicationContext.builder().build()
            ctx.start()
            def eventsListener = ctx.getBean(EventsListener)
            def embeddedApplication = ctx.getBean(EmbeddedApplication)
            embeddedApplication.start()
            embeddedApplication.stop()
//            ctx.stop()
        then:
            !ctx.isRunning()
            eventsListener.started
            eventsListener.shutdown
            eventsListener.serverStarted
            eventsListener.serverShutdown
    }

}
