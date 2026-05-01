package io.micronaut.servlet.http.server.jdk.tests;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.server.util.HttpHostResolver;
import jakarta.inject.Singleton;
import org.jspecify.annotations.NonNull;

@Singleton
@Requires(property = "spec.name", value = "CorsSimpleRequestTest")
@Replaces(HttpHostResolver.class)
public class HttpHostResolverReplacement implements HttpHostResolver {

    @Override
    @NonNull
    public String resolve(HttpRequest request) {
        return "http://localhost:8080";
    }
}
