package io.micronaut.http.poja

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Replaces
import io.micronaut.http.poja.rawhttp.ServerlessApplication
import io.micronaut.runtime.ApplicationConfiguration
import jakarta.inject.Inject
import jakarta.inject.Singleton
import spock.lang.Specification

import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.Pipe
import java.nio.charset.StandardCharsets
/**
 * A base class for serverless application test
 */
abstract class BaseServerlessApplicationSpec extends Specification {

    @Inject
    TestingServerlessApplication app

    /**
     * An extension of {@link io.micronaut.http.poja.rawhttp.ServerlessApplication} that creates 2
     * pipes to communicate with the server and simplifies reading and writing to them.
     */
    @Singleton
    @Replaces(ServerlessApplication.class)
    static class TestingServerlessApplication extends ServerlessApplication {

        OutputStream input
        Pipe.SourceChannel output
        StringBuffer readInfo = new StringBuffer()
        int lastIndex = 0

        /**
         * Default constructor.
         *
         * @param applicationContext The application context
         * @param applicationConfiguration The application configuration
         */
        TestingServerlessApplication(ApplicationContext applicationContext, ApplicationConfiguration applicationConfiguration) {
            super(applicationContext, applicationConfiguration)
        }

        @Override
        ServerlessApplication start() {
            var inputPipe = Pipe.open()
            var outputPipe = Pipe.open()
            input = Channels.newOutputStream(inputPipe.sink())
            output = outputPipe.source()

            // Run the request handling on a new thread
            new Thread(() -> {
                start(
                        Channels.newInputStream(inputPipe.source()),
                        Channels.newOutputStream(outputPipe.sink())
                )
            }).start()

            // Run the reader thread
            new Thread(() -> {
                ByteBuffer buffer = ByteBuffer.allocate(1024)
                try {
                    while (true) {
                        buffer.clear()
                        int bytes = output.read(buffer)
                        if (bytes == -1) {
                            break
                        }
                        buffer.flip()

                        Character character
                        while (buffer.hasRemaining()) {
                            character = (char) buffer.get()
                            readInfo.append(character)
                        }
                    }
                } catch (ClosedByInterruptException ignored) {
                }
            }).start()

            return this
        }

        void write(String content) {
            input.write(content.getBytes(StandardCharsets.UTF_8))
        }

        String read(int waitMillis = 300) {
            // Wait the given amount of time. The approach needs to be improved
            Thread.sleep(waitMillis)

            var result = readInfo.toString().substring(lastIndex)
            lastIndex += result.length()

            return result
                    .replace('\r', '')
                    .replaceAll("Date: .*\n", "")
        }
    }

}
