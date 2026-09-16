package io.micronaut.servlet.docs.servletannotation

import io.micronaut.context.annotation.Requires
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import jakarta.servlet.http.HttpServletRequest

/**
 * Reports whether the filter registered by [MyFilterFactory] ran for the request, so the
 * documented mappings and order can be asserted.
 */
@Requires(property = "spec.name", value = "MyFilterFactoryTest")
@Controller
class FilterAttributeController {

    @Get(value = "/extra-filter/attribute", produces = [MediaType.TEXT_PLAIN])
    fun extraFilter(request: HttpServletRequest): String = java.lang.String.valueOf(request.getAttribute("runFirst"))

    @Get(value = "/mapped-filter/attribute", produces = [MediaType.TEXT_PLAIN])
    fun mappedFilter(request: HttpServletRequest): String = java.lang.String.valueOf(request.getAttribute("runFirst"))

    @Get(value = "/unfiltered/attribute", produces = [MediaType.TEXT_PLAIN])
    fun unfiltered(request: HttpServletRequest): String = java.lang.String.valueOf(request.getAttribute("runFirst"))
}
