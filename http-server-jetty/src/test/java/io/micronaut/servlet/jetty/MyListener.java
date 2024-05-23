package io.micronaut.servlet.jetty;

import jakarta.inject.Singleton;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

@WebListener
@Singleton
public class MyListener implements ServletContextListener {
    public boolean initialized;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        initialized = true;
    }
}
