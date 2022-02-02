package io.micronaut.servlet.tomcat

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Post
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.cookie.Cookie
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.session.Session
import spock.lang.AutoCleanup
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

@IgnoreIf({ jvm.javaSpecificationVersion == '17' })
class TomcatSessionSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedServer embeddedServer = ApplicationContext.run(EmbeddedServer, ['spec.name': 'TomcatSessionSpec'])

    void "test interacting with a session"() {
        def client = embeddedServer.applicationContext.getBean(SessionClient)

        when:
        def resp = client.cart(null)
        def cookie = resp.getCookie("SESSION").orElse(null)

        then:
        cookie != null
        resp.body().isEmpty()

        when:
        client.add("eggs", cookie)
        client.add("milk", cookie)
        resp = client.cart(cookie)

        then:
        resp.body() == ["eggs", "milk"]
    }

    @Requires(property = 'spec.name', value = 'TomcatSessionSpec')
    @Client('/session')
    static interface SessionClient  {

        @Get("/cart")
        HttpResponse<List<String>> cart(@Nullable Cookie cookie)

        @Post("/add/{item}")
        HttpResponse add(String item, Cookie cookie)
    }

    @Requires(property = 'spec.name', value = 'TomcatSessionSpec')
    @Controller("/session")
    static class SessionController {

        @Get("/cart")
        List<String> cart(Session session) {
            def cart = session.get("cart", Cart.class).orElse(null)
            if (cart == null) {
                cart = new Cart(items: [])
                session.put("cart", cart)
            }
            return cart.items
        }

        @Post("/add/{item}")
        void add(Session session, String item) {
            def cart = session.get("cart", Cart.class).orElse(null)
            if (cart == null) {
                session.put("cart", new Cart(items: [item]))
            } else {
                cart.items.add(item)
            }
        }

    }

    static class Cart {
        List<String> items
    }
}
