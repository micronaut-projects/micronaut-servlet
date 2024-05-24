package io.micronaut.servlet.jetty;

import jakarta.servlet.FilterChain;
import jakarta.servlet.GenericFilter;
import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.annotation.WebServlet;
import java.io.IOException;
import java.io.PrintWriter;

@WebFilter(value = "/extra-filter/*")
public class MyExtraFilter extends GenericFilter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (!Boolean.TRUE.equals(request.getAttribute("runFirst"))) {
            throw new IllegalStateException("Should have run second");
        }
        request.setAttribute("runSecond", true);
        PrintWriter writer = res.getWriter();
        res.setContentType("text/plain");
        writer.write("My Filter!");
        writer.flush();
    }
}
