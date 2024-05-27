package io.micronaut.servlet.jetty;


import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import jakarta.servlet.FilterChain;
import jakarta.servlet.GenericFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import java.io.IOException;

@WebFilter(value = {"/extra-filter/*", "/extra-servlet/*"})
@Order(Ordered.LOWEST_PRECEDENCE)
public class MyLastFilter extends GenericFilter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!Boolean.TRUE.equals(request.getAttribute("runFirst"))) {
            throw new IllegalStateException("Should have run last");
        }
        request.setAttribute("runSecond", true);
        chain.doFilter(request, response);
    }
}
