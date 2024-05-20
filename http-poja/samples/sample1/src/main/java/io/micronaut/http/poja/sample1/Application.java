package io.micronaut.http.poja.sample1;

import io.micronaut.runtime.Micronaut;

/**
 * This program demonstrates how to use Micronaut HTTP Router without Netty.
 * It reads HTTP requests from stdin and writes HTTP responses to stdout.
 *
 * @author Sahoo.
 */
public class Application {

    public static void main(String[] args) throws Exception {
        // Need to disable banner because Micronaut prints banner to STDOUT,
        // which gets mixed with HTTP response.
        // See GCN-4489
        Micronaut.build(args)
                .banner(false)
                .mainClass(Application.class)
                .start();
        Micronaut.run(Application.class, args);
    }
}

