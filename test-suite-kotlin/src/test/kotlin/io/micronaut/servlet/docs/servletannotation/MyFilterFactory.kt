package io.micronaut.servlet.docs.servletannotation

// tag::class[]
import io.micronaut.context.annotation.Factory
import io.micronaut.core.annotation.Order
import io.micronaut.core.order.Ordered
import io.micronaut.servlet.api.annotation.ServletFilterBean
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.GenericFilter
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
// end::class[]
import io.micronaut.context.annotation.Requires

@Requires(property = "spec.name", value = "MyFilterFactoryTest")
// tag::class[]

@Factory // <1>
class MyFilterFactory {

    @ServletFilterBean(
        filterName = "another", // <2>
        value = ["/extra-filter/*", "\${my.filter.mapping}"]) // <3>
    @Order(Ordered.HIGHEST_PRECEDENCE) // <4>
    fun myOtherFilter(): Filter = object : GenericFilter() {
        override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
            request.setAttribute("runFirst", true)
            chain.doFilter(request, response)
        }
    }
}
// end::class[]
