# Micronaut Servlet

[![Maven Central](https://img.shields.io/maven-central/v/io.micronaut.servlet/micronaut-servlet-core.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.micronaut.servlet%22%20AND%20a:%22micronaut-servlet-core%22)
[![Build Status](https://github.com/micronaut-projects/micronaut-servlet/workflows/Java%20CI/badge.svg)](https://github.com/micronaut-projects/micronaut-servlet/actions)

This project includes integration between [Micronaut](http://micronaut.io) and Servlet containers.

## Documentation

<!-- See the [Documentation](https://micronaut-projects.github.io/micronaut-servlet/latest/guide) for more information. -->

See the [Snapshot Documentation](https://micronaut-projects.github.io/micronaut-servlet/snapshot/guide) for the current development docs.

## Snapshots and Releases

Snaphots are automatically published to [JFrog OSS](https://oss.jfrog.org/artifactory/oss-snapshot-local/) using [Github Actions](https://github.com/micronaut-projects/micronaut-servlet/actions).

See the documentation in the [Micronaut Docs](https://docs.micronaut.io/latest/guide/index.html#usingsnapshots) for how to configure your build to use snapshots.

Releases are published to JCenter and Maven Central via [Github Actions](https://github.com/micronaut-projects/micronaut-servlet/actions).

A release is performed with the following steps:

* [Publish the draft release](https://github.com/micronaut-projects/micronaut-servlet/releases). There should be already a draft release created, edit and publish it. The Git Tag should start with `v`. For example `v1.0.0`.
* [Monitor the Workflow](https://github.com/micronaut-projects/micronaut-servlet/actions?query=workflow%3ARelease) to check it passed successfully.
* Celebrate!
