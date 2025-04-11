
//package io.micronaut.servlet.jetty.servlet;
//
//import io.micronaut.context.annotation.Property;
//import io.micronaut.http.HttpRequest;
//import io.micronaut.http.HttpResponse;
//import io.micronaut.http.client.BlockingHttpClient;
//import io.micronaut.http.client.HttpClient;
//import io.micronaut.http.client.annotation.Client;
//import io.micronaut.serde.annotation.Serdeable;
//import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
//import jakarta.inject.Inject;
//import jakarta.servlet.ServletContext;
//import jakarta.servlet.ServletOutputStream;
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//import jakarta.ws.rs.GET;
//import jakarta.ws.rs.POST;
//import jakarta.ws.rs.Path;
//import jakarta.ws.rs.Produces;
//import jakarta.ws.rs.core.Context;
//import jakarta.ws.rs.core.HttpHeaders;
//import jakarta.ws.rs.core.Response;
//import jakarta.ws.rs.core.SecurityContext;
//import jakarta.ws.rs.core.UriInfo;
//import org.junit.jupiter.api.Assertions;
//import org.junit.jupiter.api.Test;
//
//import java.io.IOException;
//import java.nio.charset.StandardCharsets;
//
//@MicronautTest
//@Property(name = "micronaut.server.testing.async", value = "false")
//public class ServletResourceTest {
//    @Inject
//    @Client("/servlet-test")
//    HttpClient client;
//
//    @Test
//    void testJaxRsServletResource() {
//        BlockingHttpClient blockingClient = client.toBlocking();
//        HttpResponse<String> response = blockingClient.exchange(HttpRequest.POST("/post-test", new Foo("test")), String.class);
//
//        String body = response.body();
//        Assertions.assertEquals("Hello World", body);
//
//        HttpResponse<String> getResponse = blockingClient.exchange("/", String.class);
//        Assertions.assertEquals(
//            "ok",
//            getResponse.body()
//        );
//        Assertions.assertEquals(
//            "text/plain;charset=utf-8",
//            getResponse.header(HttpHeaders.CONTENT_TYPE)
//        );
//    }
//}
//
//@Path("/servlet-test")
//class ServletResource {
//
//    @GET
//    @Produces("text/plain")
//    public Response get() {
//        return Response
//            .accepted("ok")
//            .header(HttpHeaders.CONTENT_TYPE, "text/plain;charset=utf-8").build();
//    }
//
//    @POST
//    @Path("/post-test")
//    void processResponse(
////      Foo body, TODO: JAX-RS doesn't need @Body
//        @Context HttpHeaders headers,
//        @Context HttpServletRequest request,
////      @Context Request jaxrsRequest, TODO: Support Request type
//        @Context HttpServletResponse response,
//        @Context ServletContext servletContext,
////      @Context ServletConfig servletConfig, TODO: Support inject ServletConfig
//        @Context SecurityContext securityContext,
//        @Context UriInfo uriInfo) throws IOException {
////        Assertions.assertNotNull(body);
//        Assertions.assertNotNull(headers);
//        Assertions.assertNotNull(request);
//        Assertions.assertNotNull(response);
//        Assertions.assertNotNull(servletContext);
////        Assertions.assertNotNull(servletConfig);
//        Assertions.assertNotNull(securityContext);
//        Assertions.assertNotNull(uriInfo);
//        ServletOutputStream outputStream = response.getOutputStream();
//        response.setContentType("text/plain");
//        outputStream.write("Hello World".getBytes(StandardCharsets.UTF_8));
//        outputStream.flush();
//    }
//
//}
//
//@Serdeable
//record Foo(String name) {
//}
