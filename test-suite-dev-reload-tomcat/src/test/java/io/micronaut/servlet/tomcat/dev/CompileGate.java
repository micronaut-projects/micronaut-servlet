/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.servlet.tomcat.dev;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * An annotation processor on the processor path of the application a test reloads, which holds a compilation in its
 * first round while the file named by {@value #GATE} exists, so that a test can make requests while a batch compiles.
 * It creates the file {@code <gate>.entered} once it holds.
 */
public final class CompileGate extends AbstractProcessor {

    /**
     * The system property naming the gate file.
     */
    public static final String GATE = "servlet.dev.compile-gate";

    private boolean held;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        String gate = System.getProperty(GATE);
        if (held || gate == null) {
            return false;
        }
        held = true;
        Path file = Path.of(gate);
        if (!Files.exists(file)) {
            return false;
        }
        try {
            Files.writeString(Path.of(gate + ".entered"), "");
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (Files.exists(file) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }
}
