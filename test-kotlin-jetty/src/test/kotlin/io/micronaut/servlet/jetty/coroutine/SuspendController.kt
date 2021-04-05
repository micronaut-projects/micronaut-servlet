package io.micronaut.servlet.jetty.coroutine

import io.micronaut.http.*
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Error
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces
import io.micronaut.http.annotation.Status
import io.micronaut.scheduling.TaskExecutors
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Named

@Controller("/suspend")
class SuspendController(
        @Named(TaskExecutors.IO) private val executor: ExecutorService,
        private val suspendService: SuspendService,
        private val suspendRequestScopedService: SuspendRequestScopedService
    ) {

    private val coroutineDispatcher: CoroutineDispatcher

    init {
        coroutineDispatcher = executor.asCoroutineDispatcher()
    }

    @Get("/simple", produces = [MediaType.TEXT_PLAIN])
    suspend fun simple(): String { // <1>
        return "Hello"
    }

    @Get("/delayed", produces = [MediaType.TEXT_PLAIN])
    suspend fun delayed(): String { // <1>
        delay(1) // <2>
        return "Delayed"
    }

    @Status(HttpStatus.CREATED) // <1>
    @Get("/status")
    suspend fun status() {
    }

    @Status(HttpStatus.CREATED)
    @Get("/statusDelayed")
    suspend fun statusDelayed() {
        delay(1)
    }

    val count = AtomicInteger(0)

    @Get("/count")
    suspend fun count(): Int { // <1>
        return count.incrementAndGet()
    }

    @Get("/greet")
    suspend fun suspendingGreet(name: String, request: HttpRequest<String>): HttpResponse<out Any> {
        val json = "{\"message\":\"hello\"}"
        return HttpResponse.ok(json).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
    }

    @Get("/illegal")
    suspend fun illegal() {
        throw IllegalArgumentException()
    }

    @Get("/illegalWithContext")
    suspend fun illegalWithContext(): String = withContext(coroutineDispatcher) {
        throw IllegalArgumentException()
    }

    @Status(HttpStatus.BAD_REQUEST)
    @Error(exception = IllegalArgumentException::class)
    @Produces(MediaType.TEXT_PLAIN)
    suspend fun onIllegalArgument(e: IllegalArgumentException): String {
        return "illegal.argument"
    }

    @Get("/callSuspendServiceWithRetries")
    suspend fun callSuspendServiceWithRetries(): String {
        return suspendService.delayedCalculation1()
    }

    @Get("/callSuspendServiceWithRetriesBlocked")
    fun callSuspendServiceWithRetriesBlocked(): String {
        // Bypass ContinuationArgumentBinder
        return runBlocking {
            suspendService.delayedCalculation2()
        }
    }

    @Get("/callSuspendServiceWithRetriesWithoutDelay")
    suspend fun callSuspendServiceWithRetriesWithoutDelay(): String {
        return suspendService.calculation3()
    }

    @Get("/keepRequestScopeInsideCoroutine")
    suspend fun keepRequestScopeInsideCoroutine() = coroutineScope {
        val before = "${suspendRequestScopedService.requestId},${Thread.currentThread().id}"
        val after = async { "${suspendRequestScopedService.requestId},${Thread.currentThread().id}" }.await()
        "$before,$after"
    }

    @Get("/keepRequestScopeInsideCoroutineWithRetry")
    suspend fun keepRequestScopeInsideCoroutineWithRetry() = coroutineScope {
        val before = "${suspendRequestScopedService.requestId},${Thread.currentThread().id}"
        val after = async { suspendService.requestScopedCalculation() }.await()
        "$before,$after"
    }

    @Get("/keepRequestScopeAfterSuspend")
    suspend fun keepRequestScopeAfterSuspend(): String {
        val before = "${suspendRequestScopedService.requestId},${Thread.currentThread().id}"
        delay(10) // suspend
        val after = "${suspendRequestScopedService.requestId},${Thread.currentThread().id}"
        return "$before,$after"
    }
}
