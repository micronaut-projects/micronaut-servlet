/*
 * Copyright 2017-2023 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.servlet.engine.initializer;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.BeanRegistration;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanIdentifier;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.servlet.engine.DefaultMicronautServlet;
import io.micronaut.servlet.engine.MicronautServletConfiguration;
import jakarta.inject.Inject;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.annotation.WebServlet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

import static io.micronaut.core.util.StringUtils.isNotEmpty;

/**
 * A servlet initializer for Micronaut for deployment as a WAR file.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Prototype
public class MicronautServletInitializer implements ServletContainerInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(MicronautServletInitializer.class);
    private static final AnnotationValue<WebServlet> EMPTY_WEB_SERVLET = new AnnotationValue<>(WebServlet.class.getName());
    private static final String MEMBER_URL_PATTERNS = "urlPatterns";
    private static final String MEMBER_LOAD_ON_STARTUP = "loadOnStartup";
    private static final String MEMBER_ASYNC_SUPPORTED = "asyncSupported";
    private static final String MEMBER_INIT_PARAMS = "initParams";
    private static final DispatcherType[] DEFAULT_DISPATCHER_TYPES = {DispatcherType.REQUEST};
    private ApplicationContext applicationContext;
    private List<String> micronautServletMappings = new ArrayList<>();

    @Inject
    public MicronautServletInitializer(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public MicronautServletInitializer() {
    }

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) {
        final ApplicationContext applicationContext = this.applicationContext != null ? this.applicationContext : buildApplicationContext(ctx)
                .build()
                .start();
        final MicronautServletConfiguration configuration = applicationContext.getBean(MicronautServletConfiguration.class);
        Collection<BeanRegistration<Servlet>> servlets = applicationContext.getBeanRegistrations(Servlet.class);
        Collection<BeanRegistration<Filter>> filters = applicationContext.getBeanRegistrations(Filter.class);
        Collection<EventListener> servletListeners = applicationContext.getBeansOfType(EventListener.class, Qualifiers.byStereotype(WebListener.class));
        int servletOrder = 0;
        for (BeanRegistration<Servlet> servlet : servlets) {
            Servlet servletBean = servlet.getBean();
            String servletName = resolveName(servlet.getIdentifier(), servlet.getBeanDefinition());
            ServletRegistration.Dynamic registration = ctx.addServlet(servletName, servletBean);
            servletOrder = configureServletBean(
                servlet,
                servletName,
                configuration,
                servletOrder,
                registration,
                applicationContext
            );
        }
        for (BeanRegistration<Filter> beanRegistration : filters) {
            handleFilterRegistration(ctx, beanRegistration);
        }

        for (EventListener servletListener : servletListeners) {
            ctx.addListener(servletListener);
        }

    }

    private static void handleFilterRegistration(ServletContext ctx, BeanRegistration<Filter> beanRegistration) {
        Filter filter = beanRegistration.getBean();
        BeanIdentifier identifier = beanRegistration.getIdentifier();
        BeanDefinition<Filter> beanDefinition = beanRegistration.getBeanDefinition();
        String filterName = resolveName(identifier, beanDefinition);
        FilterRegistration.Dynamic registration = ctx.addFilter(filterName, filter);
        AnnotationValue<WebFilter> webFilterAnn = beanDefinition.findAnnotation(WebFilter.class).orElse(new AnnotationValue<>(WebFilter.class.getName()));
        DispatcherType[] dispatcherTypes = webFilterAnn.enumValues("dispatcherTypes", DispatcherType.class);
        if (ArrayUtils.isEmpty(dispatcherTypes)) {
            dispatcherTypes = DEFAULT_DISPATCHER_TYPES;
        }

        @NonNull String[] urlPatterns = ArrayUtils.concat(
            webFilterAnn.stringValues(),
            webFilterAnn.stringValues(MEMBER_URL_PATTERNS)
        );
        @NonNull String[] servletNames = webFilterAnn.stringValues("servletNames");
        EnumSet<DispatcherType> enumSet;
        if (dispatcherTypes.length > 1) {
            enumSet = EnumSet.of(dispatcherTypes[0], Arrays.copyOfRange(dispatcherTypes, 1, dispatcherTypes.length));
        } else {
            enumSet = EnumSet.of(dispatcherTypes[0]);
        }

        if (ArrayUtils.isNotEmpty(urlPatterns)) {
            registration.addMappingForUrlPatterns(
                enumSet,
                true,
                urlPatterns
            );
        }
        if (ArrayUtils.isNotEmpty(servletNames)) {
            registration.addMappingForUrlPatterns(
                enumSet,
                true,
                servletNames
            );
        }
        setInitParams(webFilterAnn, registration);
        registration.setAsyncSupported(webFilterAnn.booleanValue(MEMBER_ASYNC_SUPPORTED).orElse(false));
    }

    private static String resolveName(BeanIdentifier identifier, BeanDefinition<?> definition) {
        String name = identifier.getName();
        return name.equals("Primary") ? definition.getBeanType().getName() : name;
    }

    private int configureServletBean(BeanRegistration<Servlet> servlet, String servletName, MicronautServletConfiguration configuration, int order, ServletRegistration.Dynamic registration, ApplicationContext applicationContext) {
        BeanDefinition<Servlet> beanDefinition = servlet.getBeanDefinition();
        AnnotationValue<WebServlet> webServletAnnotationValue = beanDefinition
            .findAnnotation(WebServlet.class)
            .orElse(EMPTY_WEB_SERVLET);
        boolean isMicronautServlet = DefaultMicronautServlet.NAME.equals(servletName);
        @NonNull String[] urlPatterns = getUrlPatterns(webServletAnnotationValue, beanDefinition, isMicronautServlet, configuration);
        int loadOnStartup = webServletAnnotationValue.intValue(MEMBER_LOAD_ON_STARTUP).orElse(order++);
        boolean isAsyncSupported = webServletAnnotationValue.booleanValue(MEMBER_ASYNC_SUPPORTED).orElse(configuration.isAsyncSupported());

        registration.addMapping(urlPatterns);
        registration.setLoadOnStartup(loadOnStartup);
        registration.setAsyncSupported(isAsyncSupported);
        setInitParams(webServletAnnotationValue, registration);
        MultipartConfigElement multipartConfigElement = getMultipartConfig(beanDefinition, isMicronautServlet, configuration);
        if (multipartConfigElement != null) {
            registration.setMultipartConfig(multipartConfigElement);
        }
        ServletSecurity servletSecurity = beanDefinition.synthesizeDeclared(ServletSecurity.class);
        if (servletSecurity != null) {
            registration.setServletSecurity(new ServletSecurityElement(servletSecurity));
        } else if (isMicronautServlet) {
            applicationContext.findBean(ServletSecurityElement.class)
                .ifPresent(registration::setServletSecurity);
        }
        return order;
    }

    private @NonNull String[] getUrlPatterns(AnnotationValue<WebServlet> webServletAnnotationValue, BeanDefinition<Servlet> beanDefinition, boolean isMicronautServlet, MicronautServletConfiguration configuration) {
        @NonNull String[] urlPatterns =
            ArrayUtils.concat(
                webServletAnnotationValue.stringValues(),
                beanDefinition.stringValues(MEMBER_URL_PATTERNS)
            );
        if (ArrayUtils.isEmpty(urlPatterns) && isMicronautServlet) {
            urlPatterns = ArrayUtils.concat(micronautServletMappings.toArray(String[]::new), configuration.getMapping());
        }
        return urlPatterns;
    }

    private static void setInitParams(AnnotationValue<WebServlet> webServletAnnotationValue, ServletRegistration.Dynamic registration) {
        webServletAnnotationValue.getAnnotations(MEMBER_INIT_PARAMS, WebInitParam.class).forEach(av -> {
                av.stringValue("name").ifPresent(name ->
                    av.stringValue().ifPresent(value ->
                        registration.setInitParameter(name, value)
                    )
                );
        });
    }

    private static void setInitParams(AnnotationValue<WebFilter> webFilter, FilterRegistration.Dynamic registration) {
        webFilter.getAnnotations(MEMBER_INIT_PARAMS, WebInitParam.class).forEach(av -> {
            av.stringValue("name").ifPresent(name ->
                av.stringValue().ifPresent(value ->
                    registration.setInitParameter(name, value)
                )
            );
        });
    }

    private MultipartConfigElement getMultipartConfig(BeanDefinition<Servlet> beanDefinition, boolean isMicronautServlet, MicronautServletConfiguration configuration) {
        return beanDefinition.findAnnotation(MultipartConfig.class)
                .map(this::toMultipartElement)
                .orElse(isMicronautServlet ? configuration.getMultipartConfigElement().orElse(null) : null);
    }

    private MultipartConfigElement toMultipartElement(AnnotationValue<MultipartConfig> annotation) {
        String location = annotation.stringValue("location").orElse("");
        long maxFileSize = annotation.longValue("maxFileSize").orElse(-1);
        long maxRequestSize = annotation.longValue("maxRequestSize").orElse(-1);
        int fileSizeThreshold = annotation.intValue("fileSizeThreshold").orElse(-1);
        return new MultipartConfigElement(
            location,
            maxFileSize,
            maxRequestSize,
            fileSizeThreshold
        );
    }

    /**
     * Builds the application context.
     * @param ctx The context
     * @return The application context builder
     */
    protected ApplicationContextBuilder buildApplicationContext(ServletContext ctx) {
        ApplicationContextBuilder contextBuilder = ApplicationContext
            .builder()
            .classLoader(ctx.getClassLoader())
            .singletons(ctx);

        // If deployed as ROOT.war (to the root context, this will be empty)
        final String servletContextPath = ctx.getContextPath();
        if (isNotEmpty(servletContextPath)) {
            // We are loaded into a non-root context. Set the context path as a property.
            if (LOG.isDebugEnabled()) {
                LOG.debug("Setting micronaut context-path to match servlet context path: '{}'", servletContextPath);
            }
            contextBuilder.properties(Map.of("micronaut.server.context-path", servletContextPath));
        }

        return contextBuilder;
    }

    /**
     * Adds an additional mapping for the micronaut servlet.
     * @param mapping The mapping.
     */
    public void addMicronautServletMapping(String mapping) {
        if (StringUtils.isNotEmpty(mapping)) {
            this.micronautServletMappings.add(mapping);
        }
    }
}
