package io.micronaut.servlet.tomcat

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.context.event.BeanCreatedEvent
import io.micronaut.context.event.BeanCreatedEventListener
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.runtime.graceful.GracefulShutdownCapable
import jakarta.inject.Singleton
import org.apache.catalina.connector.Connector
import reactor.core.publisher.Mono
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A request the container times out is still released: without the async listener the handler's completion never
 * ran, so the request stayed counted as active and held a graceful shutdown up for the whole grace period.
 */
class TomcatAsyncTimeoutSpec extends Specification {

    void "a request the container times out is answered and no longer counted as active"() {
        given: "a connector with a short asynchronous timeout"
        EmbeddedServer server = ApplicationContext.run(EmbeddedServer, [
            'spec.name'                                   : 'TomcatAsyncTimeoutSpec',
            'micronaut.lifecycle.graceful-shutdown.enabled': true,
        ])
        GracefulShutdownCapable capable = server as GracefulShutdownCapable

        when: "a route whose publisher never completes"
        HttpResponse<String> response
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(HttpRequest.newBuilder(server.URI.resolve('/async-timeout/never')).build(), HttpResponse.BodyHandlers.ofString())
        }

        then: "the container ends it with a server error"
        response.statusCode() == 500

        and: "the request is no longer in flight"
        new PollingConditions(timeout: 5).eventually {
            capable.reportActiveTasks().orElseThrow() == 0L
        }

        cleanup:
        server.close()
    }

    @Requires(property = 'spec.name', value = 'TomcatAsyncTimeoutSpec')
    @Singleton
    static class ShortAsyncTimeout implements BeanCreatedEventListener<Connector> {
        @Override
        Connector onCreated(BeanCreatedEvent<Connector> event) {
            event.bean.asyncTimeout = 500
            event.bean
        }
    }

    @Requires(property = 'spec.name', value = 'TomcatAsyncTimeoutSpec')
    @Controller('/async-timeout')
    static class NeverController {

        @Get(value = '/never', produces = MediaType.TEXT_PLAIN)
        Mono<String> never() {
            Mono.never()
        }
    }
}
