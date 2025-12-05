package io.micronaut.servlet.jetty.servlet

import io.micronaut.context.annotation.Property
import org.jspecify.annotations.Nullable
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Header
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Produces
import io.micronaut.http.client.BlockingHttpClient
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.servlet.http.HttpServletResponse
import spock.lang.Specification

@MicronautTest
@Property(name = "micronaut.server.testing.async", value = "false")
class ServletBodyTest extends Specification {

    @Inject
    @Client("/") HttpClient client

    void testInheritAnnotations() {
        when:
            BlockingHttpClient clientBlocking = client.toBlocking()
            HttpResponse<SimpleRobot> response = clientBlocking.exchange(
                    HttpRequest.POST("/20180828/simpleRobots", new CreateSimpleRobotDetails("my type")), SimpleRobot.class)

        then:
            200 == response.getStatus().getCode()
            "my type" == response.body().type
            response.header("request-id")
    }


    @Controller("/20180828")
    static class RobotResource extends AbstractSimpleRobotBaseResource {
        @Override
        SimpleRobot createSimpleRobot(CreateSimpleRobotDetails createSimpleRobotDetails,
                                      @Nullable String opcRetryToken,
                                      @Nullable String opcRequestId,
                                      HttpHeaders httpHeadersContext,
                                      HttpServletResponse httpServletResponse) {
            httpServletResponse.addHeader("request-id", UUID.randomUUID().toString())
            return new SimpleRobot(UUID.randomUUID().toString(), createSimpleRobotDetails.type())
        }
    }

    @Produces(["application/json"])
    static abstract class AbstractSimpleRobotBaseResource {
        @Post("/simpleRobots")
        @Produces("application/json")

        abstract SimpleRobot createSimpleRobot(
                @Body CreateSimpleRobotDetails createSimpleRobotDetails,
                @Nullable @Header("opc-retry-token") String opcRetryToken,
                @Nullable  @Header("opc-request-id") String opcRequestId,
                HttpHeaders httpHeadersContext,
                HttpServletResponse httpServletResponse
        );

    }

}
