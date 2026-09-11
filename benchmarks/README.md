# Servlet runtime benchmarks

A small load-test harness for comparing the servlet runtimes with each other, with the Netty server and across
changes. It is a standalone Gradle project and is not part of the main build; it needs `wrk` on the path and
JDK 25.

The application exposes three endpoints on port 8085: `GET /plaintext`, `GET /json` and `POST /echo` (JSON in,
JSON out). Each runtime has a task: `runNetty`, `runTomcat`, `runJetty`, `runUndertow`, `runJdk`.

Properties:

- `-PservletVersion=` the `io.micronaut.servlet` version to run (default `6.2.0-SNAPSHOT`, resolved from Maven
  local first). Publish a work-in-progress build with
  `./gradlew -PprojectVersion=6.2.0-MYCHANGE-SNAPSHOT :micronaut-servlet-api:publishToMavenLocal ...` for the
  API, core, engine and the four server modules.
- `-PmnVersion=` the micronaut-core BOM version (default `5.2.0`).
- `-Pvt=true|false` overrides `micronaut.servlet.enable-virtual-threads`; `-Ploom` turns on Netty's loom-carrier
  event loop; `-Pjvmextra="..."` appends JVM arguments.

Scripts:

- `run.sh` runs every runtime once and writes `results.txt`.
- `run-ab.sh <versionA> <versionB> [servers] [rounds]` interleaves two published versions and prints a comparison.
  This is the form of evidence a performance change should carry: single runs on a shared machine have swung by 30
  percent on unchanged code.
- `run-jfr.sh <server> <version> <endpoint> [post]` records a JFR profile while loading one endpoint;
  `jfr-top.py <file>` and `jfr-alloc.py <file>` summarise CPU samples and allocation by frame.
- `compare.py results-a.txt results-b.txt ...` tabulates several result files side by side.

Compare with Spring Boot MVC by running the same three endpoints on the same container with the same JVM flags and
200 platform threads; the numbers in the parity plan were taken that way.
