package io.micronaut.servlet.jetty.servlet;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
record SimpleRobot(String id, String type) {
}
