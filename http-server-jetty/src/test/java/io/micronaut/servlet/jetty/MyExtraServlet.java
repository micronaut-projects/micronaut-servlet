package io.micronaut.servlet.jetty;

import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebServlet;
import java.io.IOException;
import java.io.PrintWriter;

@WebServlet(value = "/extra-servlet/*")
public class MyExtraServlet extends GenericServlet {
    @Override
    public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
        if (!Boolean.TRUE.equals(req.getAttribute("runFirst")) ||
            !Boolean.TRUE.equals(req.getAttribute("runSecond"))) {
            throw new IllegalStateException("Should have run last");
        }
        try (PrintWriter writer = res.getWriter()) {
            res.setContentType("text/plain");
            writer.write("My Servlet!");
            writer.flush();
        };
    }
}
