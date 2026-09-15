package io.micronaut.servlet.annotation.processor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.context.annotation.Prototype
import io.micronaut.core.annotation.Introspected
import io.micronaut.core.beans.BeanIntrospection
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Consumes
import io.micronaut.http.annotation.PathVariable
import io.micronaut.inject.BeanDefinition
import io.micronaut.inject.ExecutableMethod
import io.micronaut.websocket.annotation.OnClose
import io.micronaut.websocket.annotation.OnError
import io.micronaut.websocket.annotation.OnMessage
import io.micronaut.websocket.annotation.OnOpen
import io.micronaut.websocket.annotation.ServerWebSocket
import jakarta.inject.Singleton

class JakartaWebSocketAnnotationSpec extends AbstractTypeElementSpec {

    private static final String IMPORTS = '''
package test;

import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import java.nio.ByteBuffer;
'''

    void "a Jakarta endpoint becomes a prototype @ServerWebSocket bean with mapped handlers"() {
        given:
        BeanDefinition definition = buildBeanDefinition('test.EchoEndpoint', IMPORTS + '''
@ServerEndpoint(value = "/echo/{room}", subprotocols = {"chat", "superchat"})
class EchoEndpoint {

    private Session session;

    @OnOpen
    public void open(Session session, @PathParam("room") String roomName) {
        this.session = session;
    }

    @OnMessage(maxMessageSize = 16384)
    public String text(String message) {
        return "echo: " + message;
    }

    @OnMessage
    public void binary(ByteBuffer data, Session s) {
    }

    @OnMessage
    public void pong(PongMessage pong) {
    }

    @OnClose
    public void close(CloseReason reason) {
    }

    @OnError
    public void error(Throwable t) {
    }
}
''')

        expect: 'the class is a WebSocket bean, one instance per connection, on the Jakarta path'
        definition.hasStereotype(ServerWebSocket)
        definition.stringValue(ServerWebSocket).get() == '/echo/{room}'
        definition.stringValue(ServerWebSocket, 'subprotocols').get() == 'chat,superchat'
        definition.scopeName.get() == Prototype.name

        and: 'the Jakarta annotation is kept, so the runtime can tell the endpoint apart'
        definition.hasAnnotation('jakarta.websocket.server.ServerEndpoint')

        and: 'every handler is an executable method carrying the Micronaut annotation next to the Jakarta one'
        method(definition, 'open').hasAnnotation(OnOpen)
        method(definition, 'open').hasAnnotation('jakarta.websocket.OnOpen')
        method(definition, 'text').hasAnnotation(OnMessage)
        method(definition, 'text').hasAnnotation('jakarta.websocket.OnMessage')
        method(definition, 'binary').hasAnnotation(OnMessage)
        method(definition, 'pong').hasAnnotation(OnMessage)
        method(definition, 'close').hasAnnotation(OnClose)
        method(definition, 'error').hasAnnotation(OnError)

        and: 'maxPayloadLength is present only where maxMessageSize was declared'
        method(definition, 'text').getAnnotation(OnMessage).intValue('maxPayloadLength').asInt == 16384
        !method(definition, 'binary').getAnnotation(OnMessage).contains('maxPayloadLength')
        !method(definition, 'pong').getAnnotation(OnMessage).contains('maxPayloadLength')

        and: 'the path parameter binds under the Jakarta name, not the parameter name'
        def roomName = method(definition, 'open').arguments.find { it.name == 'roomName' }
        roomName.annotationMetadata.stringValue(PathVariable).get() == 'room'
    }

    void "a scope the endpoint declares wins over the prototype default"() {
        given:
        BeanDefinition definition = buildBeanDefinition('test.SharedEndpoint', IMPORTS + '''
import jakarta.inject.Singleton;

@Singleton
@ServerEndpoint("/shared")
class SharedEndpoint {
    @OnMessage
    public void text(String message) {
    }
}
''')

        expect:
        definition.hasStereotype(ServerWebSocket)
        definition.scopeName.get() == Singleton.name
        !definition.hasDeclaredAnnotation(Prototype)
    }

    void "a plain decoder, encoder or configurator gets an introspection generated for it"() {
        given:
        String source = IMPORTS + '''
import jakarta.websocket.Decoder;
import jakarta.websocket.Encoder;
import jakarta.websocket.server.ServerEndpointConfig;

@ServerEndpoint(value = "/codec", decoders = PlainCodecEndpoint.PlainDecoder.class, encoders = PlainCodecEndpoint.PlainEncoder.class,
    configurator = PlainCodecEndpoint.PlainConfigurator.class)
class PlainCodecEndpoint {

    public static class PlainDecoder implements Decoder.Text<String> {
        @Override public String decode(String s) { return s; }
        @Override public boolean willDecode(String s) { return true; }
    }

    public static class PlainEncoder implements Encoder.Text<String> {
        @Override public String encode(String s) { return s; }
    }

    public static class PlainConfigurator extends ServerEndpointConfig.Configurator {
    }

    @OnMessage
    public String text(String message) {
        return message;
    }
}
'''

        BeanDefinition definition = buildBeanDefinition('test.PlainCodecEndpoint', source)
        ClassLoader classLoader = buildClassLoader('test.PlainCodecEndpoint', source)

        expect: 'the three classes are listed for introspection on the endpoint, so no reflection is needed to create them'
        definition.stringValues(Introspected, 'classNames') as Set == [
            'test.PlainCodecEndpoint$PlainDecoder', 'test.PlainCodecEndpoint$PlainEncoder', 'test.PlainCodecEndpoint$PlainConfigurator'
        ] as Set

        and: 'the introspections were generated and instantiate the classes'
        ['PlainDecoder', 'PlainEncoder', 'PlainConfigurator'].every { name ->
            BeanIntrospection introspection = (BeanIntrospection) classLoader
                .loadClass('test.$test_PlainCodecEndpoint$' + name + '$Introspection').getConstructor().newInstance()
            introspection.instantiate().class.simpleName == name
        }
    }

    void "a decoder, encoder or configurator is accepted when it is a bean or introspected"() {
        given:
        BeanDefinition definition = buildBeanDefinition('test.CodecEndpoint', IMPORTS + '''
import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Singleton;
import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;
import jakarta.websocket.server.ServerEndpointConfig;

@Introspected(classes = CodecEndpoint.Configurator.class)
@ServerEndpoint(value = "/codec", decoders = CodecEndpoint.MessageDecoder.class, encoders = CodecEndpoint.MessageEncoder.class,
    configurator = CodecEndpoint.Configurator.class)
class CodecEndpoint {

    record Message(String text) {}

    @Singleton
    static class MessageDecoder implements Decoder.Text<Message> {
        @Override public Message decode(String s) { return new Message(s); }
        @Override public boolean willDecode(String s) { return true; }
    }

    @Introspected
    static class MessageEncoder implements Encoder.Text<Message> {
        @Override public String encode(Message m) { return m.text(); }
    }

    static class Configurator extends ServerEndpointConfig.Configurator {
    }

    @OnMessage
    public Message text(Message message) {
        return message;
    }
}
''')

        expect:
        definition.hasStereotype(ServerWebSocket)
        method(definition, 'text').hasAnnotation(OnMessage)

        and: 'the bean is left alone, the introspected encoder is listed harmlessly, and the classes already listed are kept'
        definition.stringValues(Introspected, 'classNames') as List == ['test.CodecEndpoint$MessageEncoder']
        definition.classValues(Introspected, 'classes')*.name == ['test.CodecEndpoint$Configurator']
    }

    void "a binary decoder makes its object message a binary handler, so it can sit next to a text one"() {
        given:
        BeanDefinition definition = buildBeanDefinition('test.BinaryCodecEndpoint', IMPORTS + '''
import jakarta.inject.Singleton;
import jakarta.websocket.Decoder;

@ServerEndpoint(value = "/codec", decoders = BinaryCodecEndpoint.FrameDecoder.class)
class BinaryCodecEndpoint {

    record Frame(byte[] bytes) {}

    @Singleton
    static class FrameDecoder implements Decoder.Binary<Frame> {
        @Override public Frame decode(ByteBuffer b) { return new Frame(new byte[0]); }
        @Override public boolean willDecode(ByteBuffer b) { return true; }
    }

    @OnMessage
    public void text(String message) {
    }

    @OnMessage
    public void frame(Frame frame) {
    }
}
''')

        expect: 'the decoded handler is recorded as binary for the runtime, the text one is untouched'
        definition.hasStereotype(ServerWebSocket)
        method(definition, 'frame').stringValue(Consumes).get() == MediaType.APPLICATION_OCTET_STREAM
        !method(definition, 'text').hasAnnotation(Consumes)
    }

    void "a bound parameter next to the payload is never taken for the message"() {
        given:
        BeanDefinition definition = buildBeanDefinition('test.BoundEndpoint', IMPORTS + '''
import io.micronaut.http.annotation.Header;

@ServerEndpoint("/bound/{id}/{last}")
class BoundEndpoint {
    @OnMessage
    public void binary(@PathParam("id") String id, ByteBuffer data) {
    }

    @OnMessage
    public void text(@PathParam("last") boolean last, @Header String agent, String message) {
    }
}
''')

        expect: 'the String path parameter does not make the binary handler a text one, nor the boolean one a partial handler'
        definition.hasStereotype(ServerWebSocket)
        method(definition, 'binary').hasAnnotation(OnMessage)
        method(definition, 'text').hasAnnotation(OnMessage)
    }

    void "an endpoint that cannot be mapped is rejected at compilation time: #reason"() {
        when:
        buildBeanDefinition('test.BadEndpoint', IMPORTS + source)

        then:
        def e = thrown(RuntimeException)
        e.message.contains(message)

        where:
        reason                      | source                                                                   | message
        'no message handler'        | '''
@ServerEndpoint("/bad")
class BadEndpoint {
    @OnOpen
    public void open(Session session) {
    }
}
'''                                                                                                       | 'must declare at least one @OnMessage'
        'two text handlers'         | '''
@ServerEndpoint("/bad")
class BadEndpoint {
    @OnMessage
    public void text(String message) {
    }
    @OnMessage
    public void more(java.io.Reader reader) {
    }
}
'''                                                                                                       | 'more than one text @OnMessage'
        'two binary handlers'       | '''
@ServerEndpoint("/bad")
class BadEndpoint {
    @OnMessage
    public void bytes(byte[] message) {
    }
    @OnMessage
    public void buffer(ByteBuffer buffer) {
    }
}
'''                                                                                                       | 'more than one binary @OnMessage'
        'partial message handler'   | '''
@ServerEndpoint("/bad")
class BadEndpoint {
    @OnMessage
    public void text(String part, boolean last) {
    }
}
'''                                                                                                       | 'Partial message handlers are not supported'
        'message size out of range' | '''
@ServerEndpoint("/bad")
class BadEndpoint {
    @OnMessage(maxMessageSize = 4294967296L)
    public void text(String message) {
    }
}
'''                                                                                                       | 'maxMessageSize must not exceed'
        'two open handlers'         | '''
@ServerEndpoint("/bad")
class BadEndpoint {
    @OnOpen
    public void open(Session session) {
    }
    @OnOpen
    public void openAgain(Session session) {
    }
    @OnMessage
    public void text(String message) {
    }
}
'''                                                                                                       | 'more than one @OnOpen'
        'client endpoint'           | '''
@jakarta.websocket.ClientEndpoint
class BadEndpoint {
    @OnMessage
    public void text(String message) {
    }
}
'''                                                                                                       | '@ClientEndpoint is not supported'
        'decoder needing injection' | '''
import jakarta.websocket.Decoder;

@ServerEndpoint(value = "/bad", decoders = BadEndpoint.InjectedDecoder.class)
class BadEndpoint {
    public static class InjectedDecoder implements Decoder.Text<String> {
        public InjectedDecoder(io.micronaut.core.convert.ConversionService conversionService) { }
        @Override public String decode(String s) { return s; }
        @Override public boolean willDecode(String s) { return true; }
    }
    @OnMessage
    public void text(String message) {
    }
}
'''                                                                                                       | 'cannot instantiate: test.BadEndpoint$InjectedDecoder'
    }

    private static ExecutableMethod method(BeanDefinition definition, String name) {
        definition.executableMethods.find { it.methodName == name }
    }
}
