package io.micronaut.http.poja.sample1;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.Status;

/**
 * @author Sahoo.
 */
@Controller(value = "/", produces = MediaType.TEXT_PLAIN, consumes = MediaType.ALL)
public class MyResource {
    @Get
    public String index() {
        return "Hello, Micronaut Without Netty!\n";
    }

    @Delete
    public void delete() {
        System.err.println("Delete called");
    }

    @Post("/{name}")
    @Status(HttpStatus.CREATED)
    public String create(@NonNull String name) {
        return "Hello, " + name + "\n";
    }

    @Put("/{name}")
    @Status(HttpStatus.OK)
    public String update(@NonNull String name) {
        return "Hello, " + name + "!\n";
    }
}

