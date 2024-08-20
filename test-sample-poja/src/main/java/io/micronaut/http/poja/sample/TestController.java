/*
 * Copyright 2017-2024 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.poja.sample;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.Status;

/**
 * A controller for testing.
 *
 * @author Sahoo.
 */
@Controller(value = "/", produces = MediaType.TEXT_PLAIN, consumes = MediaType.ALL)
public class TestController {

    @Get
    public final String index() {
        return "Hello, Micronaut Without Netty!\n";
    }

    @Delete
    public final void delete() {
        System.err.println("Delete called");
    }

    @Post("/{name}")
    @Status(HttpStatus.CREATED)
    public final String create(@NonNull String name, HttpRequest<?> request) {
        return "Hello, " + name + "\n";
    }

    @Put("/{name}")
    @Status(HttpStatus.OK)
    public final String update(@NonNull String name) {
        return "Hello, " + name + "!\n";
    }

}

