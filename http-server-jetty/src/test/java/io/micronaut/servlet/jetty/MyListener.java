package io.micronaut.servlet.jetty;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

@WebListener
public class MyListener implements ServletContextListener {
    public static boolean initialized;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        initialized = true;
    }
}
