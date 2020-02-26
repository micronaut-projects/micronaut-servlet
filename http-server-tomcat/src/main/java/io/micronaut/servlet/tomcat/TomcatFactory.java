package io.micronaut.servlet.tomcat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.micronaut.servlet.engine.server.ServletServerFactory;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

import javax.inject.Singleton;
import java.util.function.Consumer;

@Factory
public class TomcatFactory extends ServletServerFactory {

    /**
     * Default constructor.
     * @param resourceResolver The resource resolver
     * @param serverConfiguration The server config
     * @param sslConfiguration The SSL config
     */
    protected TomcatFactory(
            ResourceResolver resourceResolver,
            TomcatConfiguration serverConfiguration,
            SslConfiguration sslConfiguration) {
        super(resourceResolver, serverConfiguration, sslConfiguration);
    }

    @Override
    public TomcatConfiguration getServerConfiguration() {
        return (TomcatConfiguration) super.getServerConfiguration();
    }

    @Singleton
    @Primary
    protected Tomcat tomcatServer(Connector connector, ApplicationContext applicationContext) {
        Tomcat tomcat = new Tomcat();
        tomcat.setHostname(getConfiguredHost());
        final String contextPath = getContextPath();
        tomcat.getHost().setAutoDeploy(false);
        tomcat.setConnector(connector);
        final String cp = contextPath != null && !contextPath.equals("/") ? contextPath : "";
        final Context context = tomcat.addContext(cp, "/");
        final Wrapper servlet = Tomcat.addServlet(
                context,
                "micronaut",
                new DefaultMicronautServlet(applicationContext)
        );
        servlet.addMapping("/*");
        getServerConfiguration()
                .getMultipartConfiguration()
                .ifPresent(servlet::setMultipartConfigElement);

        return tomcat;
    }

    /**
     * @return Create the protocol.
     */
    @Singleton
    @Primary
    protected Connector tomcatConnector() {
        final Connector tomcatConnector = getServerConfiguration().getTomcatConnector();
        tomcatConnector.setPort(getConfiguredPort());
        return tomcatConnector;
    }

}
