package io.micronaut.servlet.docs.servletapi

import io.micronaut.core.annotation.Introspected

@Introspected
class Person {
    var name: String? = null
    var age: Int = 18
}
