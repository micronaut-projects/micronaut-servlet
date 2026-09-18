# Python Docs Disabled Test Inventory

This file tracks Python docs examples of Micronaut Servlet that are present but disabled, or that deviate from the
Java example because the direct port currently fails compilation or at runtime. Use it as the bug-fixing task list
for the final migration wave.

## Reconciliation

- Last generated active `@Disabled` count: 0.
- Last generated command: `rg -n "@Disabled\\(" test-suite-python/src/test/python`.
- Last full-suite command: `./gradlew :test-suite-python:test -Ppython-ci`.
- Last full-suite result: build successful, 9 tests executed, 0 skipped, 0 failed.

## Migration Rules

- Do not define local copies of Micronaut annotation helpers or custom annotation shims in docs snippets. Standard
  Micronaut annotations are generated from imports (`from micronaut.websocket.annotation import ServerWebSocket`,
  `from micronaut.servlet.api.annotation import ServletFilterBean`, `from micronaut.context.annotation import Factory`).
- Java classes are imported, never aliased with `java.type(...)`: Servlet API types from their Java packages
  (`from jakarta.servlet.http import HttpServletRequest`, `from jakarta.servlet import Filter`,
  `from jakarta.servlet.http import Part as ServletPart` where the name clashes with the `@Part` annotation), the
  Jetty and Tomcat server classes (`from org.eclipse.jetty.server import Server`, `from org.apache.catalina.startup
  import Tomcat`), JDK classes (`from java.lang import AutoCloseable, String`, `from java.util import Base64`,
  `from java.util.concurrent import ConcurrentLinkedQueue, TimeUnit`, `from java.io import BufferedReader`) and
  Reactor (`from reactor.core.publisher import Flux`). Imported classes work as base classes (`AutoCloseable`),
  generic type arguments, type hints and as runtime class literals (`WebSocketClient.connect(ChatClientWebSocket,
  ...)`, `retrieve(..., String)`, `getUriVariables().get("topic", String, None)`).
- Undertow lives in an `io.` package: the compiler resolves `from io.undertow import Undertow`, but at runtime `io`
  is the Python standard library module, so the import is written as `try: from io.undertow import Undertow` /
  `except ImportError: from undertow import Undertow` (`TODO(python)`, compiler fix for `io.*` packages pending).
  The nested builder is referenced by attribute access, `BeanCreatedEventListener[Undertow.Builder]`; a module-level
  alias (`UndertowBuilder = Undertow.Builder`) of a conditionally imported class is not resolved by the compiler and
  the generic base degrades to `BeanCreatedEventListener[Object]`, which then receives every bean (including the ones
  created before the Python runtime exists).
- The classes the type hints name must be on the compile classpath, otherwise the stub silently degrades to `Object`
  (the server classes are `implementation` dependencies of the runtimes: the test suites add `tomcat-embed-core`
  and `undertow-servlet` explicitly).
- Methods that implement or override a Java interface keep the Java (camelCase) name (`onCreated`, `doFilter`,
  `writeTo`); other methods are snake_case.
- A Python `bytes` value does not select the `byte[]` overload of `MultipartBody.Builder.addPart` (the `String`
  one is picked, then fails); the multipart test builds a Java `byte[]` with `java.util.Base64`.
- `java.lang.String.valueOf(None)` picks the `char[]` overload and throws; the helper controller that reports the
  servlet request attribute set by the documented filter checks for `None` itself.
- A Python class cannot extend a Java class: the documented servlet filter implements `jakarta.servlet.Filter`
  instead of extending `GenericFilter`, and the `Writable` returned by the Readable/Writable example is a small
  class implementing `Writable` instead of a lambda.
- The test-suite `Test` tasks run with `enableAssertions = false`: a Truffle host-interop assertion trips on varargs
  overloads called from Python.

## Active `@Disabled` Tests

None.

## Commented Unsupported Snippet Ports

None.

## Workarounds Kept In Snippets

None.

## `java.type` usages

None.

## Intentionally Unsupported Snippet Targets

| Target | Reason |
| --- | --- |
| `io.micronaut.servlet.docs.war.CustomInitializer` (`languages="java,kotlin,groovy"`) | A WAR initializer must extend the Java class `MicronautServletInitializer` and is instantiated by the servlet container through `META-INF/services` before any application context, and therefore before the Python runtime, exists. A Python class cannot extend a Java class, and the generated stub of a Python class needs the runtime to be instantiated. The guide shows a `[.lang-python]` note instead. |
