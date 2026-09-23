package io.micronaut.servlet.docs.servletannotation

import io.micronaut.context.annotation.Requires
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import jakarta.servlet.http.HttpServletRequest

/**
 * Reports whether the filter registered by {@link MyFilterFactory} ran for the request, so the
 * documented mappings and order can be asserted.
 */
@Requires(property = "spec.name", value = "MyFilterFactoryTest")
@Controller
class FilterAttributeController {

    @Get(value = "/extra-filter/attribute", produces = MediaType.TEXT_PLAIN)
    String extraFilter(HttpServletRequest request) {
        String.valueOf(request.getAttribute("runFirst"))
    }

    @Get(value = "/mapped-filter/attribute", produces = MediaType.TEXT_PLAIN)
    String mappedFilter(HttpServletRequest request) {
        String.valueOf(request.getAttribute("runFirst"))
    }

    @Get(value = "/unfiltered/attribute", produces = MediaType.TEXT_PLAIN)
    String unfiltered(HttpServletRequest request) {
        String.valueOf(request.getAttribute("runFirst"))
    }
}
