package io.micronaut.http.server.tck.tomcat.tests;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.server.util.HttpHostResolver;
import jakarta.inject.Singleton;

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
