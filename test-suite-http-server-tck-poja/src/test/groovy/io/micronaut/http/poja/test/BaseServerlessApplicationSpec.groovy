package io.micronaut.http.poja.test

import io.micronaut.context.annotation.Replaces
import io.micronaut.http.HttpRequest
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Filter
import io.micronaut.http.filter.ServerFilterChain
import io.micronaut.http.server.tck.poja.adapter.TestingServerlessApplication
import io.micronaut.session.Session
import io.micronaut.session.SessionStore
import io.micronaut.session.http.HttpSessionFilter
import io.micronaut.session.http.HttpSessionIdEncoder
import io.micronaut.session.http.HttpSessionIdResolver
import jakarta.inject.Inject
import org.reactivestreams.Publisher
import spock.lang.Specification

/**
 * A base class for serverless application test
 */
abstract class BaseServerlessApplicationSpec extends Specification {

    @Inject
    TestingServerlessApplication app

    /**
     * Not sure why this is required
     * TODO fix this
     *
     * @author Andriy Dmytruk
     */
    @Filter("**/*")
    @Replaces(HttpSessionFilter)
    static class DisabledSessionFilter extends HttpSessionFilter {

        DisabledSessionFilter(SessionStore<Session> sessionStore, HttpSessionIdResolver[] resolvers, HttpSessionIdEncoder[] encoders) {
            super(sessionStore, resolvers, encoders)
        }

        @Override
        Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
            return chain.proceed(request)
        }
    }

}
