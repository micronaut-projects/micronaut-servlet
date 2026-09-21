# tag::class[]
from jakarta.servlet import Filter, FilterChain, ServletRequest, ServletResponse
from micronaut.context.annotation import Factory
from micronaut.core.annotation import Order
from micronaut.core.order import Ordered
from micronaut.servlet.api.annotation import ServletFilterBean
# end::class[]
from micronaut.context.annotation import Requires


# TODO(python): extending `GenericFilter` like the Java example does not compile: `doFilter` declares checked exceptions
# (`throws ServletException, IOException`) that the generated dispatcher of the Python subclass does not declare.
# tag::class[]


class RunFirstFilter(Filter):

    def doFilter(self, request: ServletRequest, response: ServletResponse, chain: FilterChain) -> None:
        request.setAttribute("runFirst", True)
        chain.doFilter(request, response)
# end::class[]


@Requires(property="spec.name", value="MyFilterFactoryTest")
# tag::class[]


@Factory  # <1>
class MyFilterFactory:

    @ServletFilterBean(
        filterName="another",  # <2>
        value=["/extra-filter/*", "${my.filter.mapping}"])  # <3>
    @Order(Ordered.HIGHEST_PRECEDENCE)  # <4>
    def my_other_filter(self) -> Filter:
        return RunFirstFilter()
# end::class[]
