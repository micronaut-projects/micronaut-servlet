package io.micronaut.servlet.jetty;

import io.micronaut.context.annotation.Factory;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.servlet.engine.annotation.ServletFilterBean;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.GenericFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;

@Factory
public class MyFilterFactory {

    @ServletFilterBean(filterName = "another", value = {"/extra-filter/*", "/extra-servlet/*"})
    @Order(Ordered.HIGHEST_PRECEDENCE)
    Filter myOtherFilter() {
        return new GenericFilter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws ServletException, IOException {
                request.setAttribute("runFirst", true);
                chain.doFilter(request, response);
            }
        };
    }
}
