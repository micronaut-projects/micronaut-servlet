package io.micronaut.servlet.undertow;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ShutdownEvent;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;

@Requires(property = "spec.name", value = "UndertowStartStopSpec")
@Singleton
public class EventsListener implements ApplicationEventListener<Object>  {

    public boolean serverStarted;
    public boolean started;
    public boolean serverShutdown;
    public boolean shutdown;

    public EventsListener(Environment environment) {
        // This will fail if the event is sent when the bean context is shutdown
    }

    @Override
    public void onApplicationEvent(Object event) {
        if (event instanceof StartupEvent) {
            if (started) {
                throw new IllegalStateException();
            }
            started = true;
        }
        if (event instanceof ServerStartupEvent) {
            if (!started || serverStarted) {
                throw new IllegalStateException();
            }
            serverStarted = true;
        }
        if (event instanceof ShutdownEvent) {
            if (!serverShutdown || shutdown) {
                throw new IllegalStateException();
            }
            shutdown = true;
        }
        if (event instanceof ServerShutdownEvent) {
            if (serverShutdown) {
                throw new IllegalStateException();
            }
            serverShutdown = true;
        }
    }
}
