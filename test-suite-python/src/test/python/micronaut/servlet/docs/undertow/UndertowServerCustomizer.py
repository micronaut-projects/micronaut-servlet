# tag::imports[]
from jakarta.inject import Singleton
from micronaut.context.event import BeanCreatedEvent, BeanCreatedEventListener

try:
    from io.undertow import Undertow
except ImportError:  # TODO(python): packages under `io.` other than `io.micronaut` cannot be imported at runtime
    from undertow import Undertow
# end::imports[]
from micronaut.context.annotation import Requires


@Requires(property="spec.name", value="UndertowServerCustomizerTest")
# tag::class[]
@Singleton
class UndertowServerCustomizer(BeanCreatedEventListener[Undertow.Builder]):

    def onCreated(self, event: BeanCreatedEvent[Undertow.Builder]) -> Undertow.Builder:
        undertow_builder = event.getBean()
        # perform customizations, for example:
        undertow_builder.setIoThreads(2)
        return undertow_builder
# end::class[]
