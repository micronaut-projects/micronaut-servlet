# tag::imports[]
from jakarta.inject import Singleton
from micronaut.context.event import BeanCreatedEvent, BeanCreatedEventListener
from org.eclipse.jetty.server import Server
# end::imports[]
from micronaut.context.annotation import Requires


@Requires(property="spec.name", value="JettyServerCustomizerTest")
# tag::class[]
@Singleton
class JettyServerCustomizer(BeanCreatedEventListener[Server]):

    def onCreated(self, event: BeanCreatedEvent[Server]) -> Server:
        jetty_server = event.getBean()
        # perform customizations, for example:
        jetty_server.setStopTimeout(5000)
        return jetty_server
# end::class[]
