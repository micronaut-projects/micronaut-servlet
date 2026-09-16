# tag::imports[]
from jakarta.inject import Singleton
from micronaut.context.event import BeanCreatedEvent, BeanCreatedEventListener
from org.apache.catalina.startup import Tomcat
# end::imports[]
from micronaut.context.annotation import Requires


@Requires(property="spec.name", value="TomcatServerCustomizerTest")
# tag::class[]
@Singleton
class TomcatServerCustomizer(BeanCreatedEventListener[Tomcat]):

    def onCreated(self, event: BeanCreatedEvent[Tomcat]) -> Tomcat:
        tomcat = event.getBean()
        # perform customizations, for example:
        tomcat.getServer().setUtilityThreads(2)
        return tomcat
# end::class[]
