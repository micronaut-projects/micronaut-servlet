package io.micronaut.http.poja

import spock.lang.Specification

import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

class InheritedChannelStreamSpec extends Specification {

    void "the input of an inherited socket channel reports the data the client has sent"() {
        given:
        ServerSocketChannel server = ServerSocketChannel.open().bind(new InetSocketAddress(InetAddress.loopbackAddress, 0))
        Socket client = new Socket(InetAddress.loopbackAddress, server.socket().localPort)
        SocketChannel channel = server.accept()
        client.outputStream.write(new byte[100])
        client.outputStream.flush()

        when:
        InputStream input = PojaHttpServerlessApplication.channelInputStream(channel)
        int available = 0
        for (int i = 0; i < 50 && available == 0; i++) {
            Thread.sleep(20)
            available = input.available()
        }

        then: "so the runtime can discard it before closing the connection"
        available == 100

        cleanup:
        client.close()
        channel.close()
        server.close()
    }
}
