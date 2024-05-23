package io.micronaut.servlet.annotation.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.context.ApplicationContext
import io.micronaut.inject.qualifiers.Qualifiers
import jakarta.servlet.Servlet

class ServletAnnotationSpec
        extends AbstractTypeElementSpec {

    void "test servlet annotated is bean"() {
        given:
        def context = buildContext('''
package test;

import jakarta.servlet.FilterChain;
import jakarta.servlet.GenericFilter;
import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.annotation.WebServlet;
import java.io.*;

@WebServlet
class MyServlet extends GenericServlet {
    @Override
    public void service(ServletRequest req, ServletResponse res)
        throws ServletException, IOException {

    }
}

@WebServlet(name = "test")
class MyServlet2 extends GenericServlet {
@Override
    public void service(ServletRequest req, ServletResponse res)
        throws ServletException, IOException {

    }
}

@WebFilter
class Filter1 extends GenericFilter {
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
    }
}
@WebFilter(filterName = "two")
class Filter2 extends GenericFilter {
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
    }
}

@WebListener
class Listener implements ServletContextListener {
}
''')
        expect:
        getBean(context, 'test.MyServlet')
        getBean(context, 'test.MyServlet2')
        getBean(context, 'test.MyServlet', Qualifiers.byName("MyServlet"))
        getBean(context, 'test.MyServlet2', Qualifiers.byName("test"))
        getBean(context, 'test.Listener')
        getBean(context, 'test.Filter1')
        getBean(context, 'test.Filter2')
    }
}
