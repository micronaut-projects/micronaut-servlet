package io.micronaut.http.poja.sample.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record Cactus(
    String color,
    int spikeSize
) {
}
