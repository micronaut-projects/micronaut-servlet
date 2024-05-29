package io.micronaut.servlet.jetty;

// tag::class[]
import io.micronaut.context.annotation.Factory;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.servlet.api.annotation.ServletFilterBean;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.GenericFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;

@Factory // <1>
public class MyFilterFactory {

    @ServletFilterBean(
        filterName = "another", // <2>
        value = {"/extra-filter/*", "${my.filter.mapping}"}) // <3>
    @Order(Ordered.HIGHEST_PRECEDENCE) // <4>
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
// end::class[]
