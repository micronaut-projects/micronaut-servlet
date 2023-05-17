package io.micronaut.http.server.tck.jetty.tests;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Status;
import io.micronaut.http.tck.AssertionUtils;
import io.micronaut.http.tck.HttpResponseAssertion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Objects;

import static io.micronaut.http.tck.TestScenario.asserts;

/**
 * This is a new test in core
 */

class BodyPartTest {

    public static final String SPEC_NAME = "BodyPartTest";

    @Test
    void testCustomBodyPOJOAsPart() throws IOException {
        asserts(SPEC_NAME,
            HttpRequest.POST("/response-body/part-pojo", "{\"point\":{\"x\":10,\"y\":20},\"foo\":\"bar\"}")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON),
            (server, request) -> AssertionUtils.assertDoesNotThrow(server, request,
                HttpResponseAssertion.builder()
                    .status(HttpStatus.CREATED)
                    .body("{\"x\":10,\"y\":20}")
                    .build()));
    }

    @Controller("/response-body")
    @Requires(property = "spec.name", value = SPEC_NAME)
    static class BodyController {

        @Post(uri = "/part-pojo")
        @Status(HttpStatus.CREATED)
        Point postPart(@Body("point") Point data) {
            return data;
        }
    }

    @Introspected
    static class Point {
        private Integer x;
        private Integer y;

        public Integer getX() {
            return x;
        }

        public void setX(Integer x) {
            this.x = x;
        }

        public Integer getY() {
            return y;
        }

        public void setY(Integer y) {
            this.y = y;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            Point point = (Point) o;

            if (!Objects.equals(x, point.x)) {
                return false;
            }
            return Objects.equals(y, point.y);
        }

        @Override
        public int hashCode() {
            int result = x != null ? x.hashCode() : 0;
            result = 31 * result + (y != null ? y.hashCode() : 0);
            return result;
        }
    }
}
