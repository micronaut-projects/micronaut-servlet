/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.servlet.jetty.docs;

// tag::imports[]
import io.micronaut.core.io.Readable;
import io.micronaut.core.io.Writable;
// end::imports[]
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.servlet.jetty.Person;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

@Controller("/docs")
public class DocsController {

    // tag::reqrep[]
    @Get("/hello")
    void process(
            HttpServletRequest request, // <1>
            HttpServletResponse response) // <2>
            throws IOException {
        response.addHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN);
        response.setStatus(HttpStatus.ACCEPTED.getCode());
        try (final PrintWriter writer = response.getWriter()) {
            writer.append("Hello ").append(request.getParameter("name"));
            writer.flush();
        }
    }
    // end::reqrep[]

    // tag::writable[]
    @Post(value = "/writable", processes = "text/plain")
    Writable readAndWrite(@Body Readable readable) throws IOException {
        return out -> {
            try (BufferedReader reader = new BufferedReader(readable.asReader())) {
                out.append("Hello ").append(reader.readLine());
            }
        };
    }
    // end::writable[]

    // tag::multipart[]
    @Post(value = "/multipart", consumes = MediaType.MULTIPART_FORM_DATA, produces = "text/plain")
    String multipart(
            String attribute, // <1>
            @Part("one") Person person, // <2>
            @Part("two") String text, // <3>
            @Part("three") byte[] bytes, // <4>
            @Part("four") javax.servlet.http.Part raw, // <5>
            @Part("five") CompletedPart part) { // <6>
        return "Ok";
    }
    // end::multipart[]
}
