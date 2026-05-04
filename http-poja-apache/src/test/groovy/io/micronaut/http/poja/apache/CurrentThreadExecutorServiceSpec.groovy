package io.micronaut.http.poja.apache

import spock.lang.Specification

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class CurrentThreadExecutorServiceSpec extends Specification {

    void "factory creates current thread executor for blocking tasks"() {
        when:
        def executor = new ApacheExecutorServiceFactory().blocking()

        then:
        executor instanceof CurrentThreadExecutorService
    }

    void "executor runs commands on the calling thread"() {
        given:
        def executor = new CurrentThreadExecutorService()
        def callingThread = Thread.currentThread()
        Thread executionThread = null

        when:
        executor.execute {
            executionThread = Thread.currentThread()
        }

        then:
        executionThread == callingThread
        !executor.isShutdown()
        !executor.isTerminated()
        !executor.awaitTermination(1, TimeUnit.MILLISECONDS)
    }

    void "shutdown prevents new work and marks executor terminated"() {
        given:
        def executor = new CurrentThreadExecutorService()

        when:
        executor.shutdown()

        then:
        executor.isShutdown()
        executor.isTerminated()
        executor.awaitTermination(1, TimeUnit.MILLISECONDS)

        when:
        executor.execute { }

        then:
        thrown(RejectedExecutionException)
    }

    void "shutdownNow returns no queued work"() {
        given:
        def executor = new CurrentThreadExecutorService()

        when:
        def queued = executor.shutdownNow()

        then:
        queued.empty
        executor.isShutdown()
        executor.isTerminated()
    }
}
