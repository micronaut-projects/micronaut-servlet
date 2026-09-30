package io.micronaut.servlet.docs.servletapi

import io.micronaut.core.annotation.Introspected

@Introspected
class Person {
    String name
    int age = 18
}
