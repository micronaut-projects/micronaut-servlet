from jakarta.servlet.http import HttpServletRequest
from micronaut.context.annotation import Requires
from micronaut.http import MediaType
from micronaut.http.annotation import Controller, Get


def attribute(request: HttpServletRequest) -> str:
    value = request.getAttribute("runFirst")
    return "null" if value is None else str(value).lower()


# Reports whether the filter registered by MyFilterFactory ran for the request, so the
# documented mappings and order can be asserted.
@Requires(property="spec.name", value="MyFilterFactoryTest")
@Controller
class FilterAttributeController:

    @Get(value="/extra-filter/attribute", produces=MediaType.TEXT_PLAIN)
    def extra_filter(self, request: HttpServletRequest) -> str:
        return attribute(request)

    @Get(value="/mapped-filter/attribute", produces=MediaType.TEXT_PLAIN)
    def mapped_filter(self, request: HttpServletRequest) -> str:
        return attribute(request)

    @Get(value="/unfiltered/attribute", produces=MediaType.TEXT_PLAIN)
    def unfiltered(self, request: HttpServletRequest) -> str:
        return attribute(request)
