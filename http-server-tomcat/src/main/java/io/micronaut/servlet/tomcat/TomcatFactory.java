package io.micronaut.servlet.tomcat;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.micronaut.servlet.engine.server.ServletServerFactory;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

import javax.inject.Singleton;

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
            HttpServerConfiguration serverConfiguration,
            SslConfiguration sslConfiguration) {
        super(resourceResolver, serverConfiguration, sslConfiguration);
    }

    @Singleton
    @Primary
    protected Tomcat tomcatServer(Connector connector) {
        Tomcat tomcat = new Tomcat();
        tomcat.setHostname(getConfiguredHost());
        final String contextPath = getContextPath();
        tomcat.getHost().setAutoDeploy(false);
        tomcat.setConnector(connector);
        final String cp = contextPath != null && !contextPath.equals("/") ? contextPath : "";
        final Context context = tomcat.addContext(cp, "/");
        Tomcat.addServlet(
                context,
                "micronaut",
                DefaultMicronautServlet.class.getName()
        ).addMapping("/*");

        return tomcat;
    }

    /**
     * @return Create the protocol.
     */
    @Singleton
    @Primary
    protected Connector tomcatConnector() {
        final Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(getConfiguredPort());
        return connector;
    }

}
