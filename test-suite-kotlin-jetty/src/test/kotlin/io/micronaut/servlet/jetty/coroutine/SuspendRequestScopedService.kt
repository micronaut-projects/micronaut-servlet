package io.micronaut.servlet.jetty.coroutine

import io.micronaut.runtime.http.scope.RequestScope
import java.util.*

@RequestScope
open class SuspendRequestScopedService {
    open val requestId = UUID.randomUUID().toString()
}
