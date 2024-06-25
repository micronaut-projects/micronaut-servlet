package io.micronaut.http.poja.test


import io.micronaut.http.server.tck.poja.adapter.TestingServerlessApplication
import jakarta.inject.Inject
import spock.lang.Specification
/**
 * A base class for serverless application test
 */
abstract class BaseServerlessApplicationSpec extends Specification {

    @Inject
    TestingServerlessApplication app

}
