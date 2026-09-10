# WebSocket support for micronaut-servlet

| | |
|---|---|
| Status | **Implemented on 6.2.x.** Jetty, Tomcat and Undertow all green. See [Implementation status](#implementation-status). |
| Target | `6.2.x` (6.2.0-SNAPSHOT) on micronaut-core `5.2.x` |
| Drafted | 2026-09-09 |
| Scope | Jetty 12 EE10, Tomcat 11, Undertow 2.3, WAR deployment on Jakarta WebSocket 2.1 containers |
| Explicitly out of scope | JDK `HttpServer` runtime, POJA, a non-Netty WebSocket client, Jakarta `@ServerEndpoint` as a Micronaut programming model |

Everything below marked **verified** was read in source (micronaut-core `5.2.0-SNAPSHOT` at `/Users/graemerocher/dev/micronaut/core`, this repository at `origin/6.2.x` = `c2c5365f`, and the container artifacts resolved from Maven Central). Items marked **to verify in the spike** are the assumptions the first implementation step must confirm before the rest is built.

---

## Implementation status

Implemented in this worktree, uncommitted. The design held: **no change was needed to any of
the three container factories**, because each already registers every
`ServletContainerInitializer` bean, and Undertow's deployment is reachable through a
`BeanCreatedEventListener`.

### What shipped

| Module | Content |
|---|---|
| `servlet-core` | `ServletWebSocketUpgrader` SPI with the RFC 6455 upgrade test, `WebSocketUpgradeRequestLifecycle`, and the upgrade branch in `ServletHttpHandler.service` |
| `servlet-websocket` (new) | Upgrader, endpoint, configurator, session with the send queue, encoder, session registry, broadcaster, configuration, handshake-request snapshot, and one bootstrap class per container |
| `http-server-jetty` / `-tomcat` / `-undertow` | Test dependencies and the ported spec only. **No production code changed.** |
| docs | `src/main/docs/guide/websocket.adoc` plus a `toc.yml` entry |
| `test-suite`, `test-suite-groovy`, `test-suite-kotlin` (new) | The guide's example endpoint and client in each language, under one shared fully qualified name, plus a test per language that exercises them. These are the module names the `snippet::` macro resolves by default, so the example renders in all three editions of the guide. |

### Test results

| Suite | Result |
|---|---|
| Jetty, Tomcat, Undertow WebSocket specs | 11 tests each, all passing |
| Guide example suites, one per language | 1 test each in `test-suite`, `test-suite-groovy`, `test-suite-kotlin` |
| `servlet-core` | 20 tests, including 10 upgrade-detection cases |
| `servlet-engine` | 32 |
| Jetty / Tomcat / Undertow module suites | 240 / 95 / 91, no regressions |
| HTTP Server TCK for Jetty, Tomcat, Undertow, JDK | all passing |

The ported coverage is: text messages with path variables and the broadcaster; POJO JSON;
binary plus ping and pong; `@OnOpen` binding of `@QueryValue`, `@Header` and `HttpRequest`;
a filter that cancels the upgrade; a non-upgrade `GET` to a WebSocket route returning 400;
an unmatched upgrade being refused; `@OnError`; an unhandled handler error closing the
connection; and `@ExecuteOn` redispatch.

### Where the implementation differs from this design

1. **The `ServerEndpointConfig` is built per upgrade, not shared.** The configurator has to
   carry the per-connection context, and `getEndpointInstance` receives no config.
2. **The context reaches the endpoint by three routes, because one is not enough.** Undertow
   rebuilds the `ServerEndpointConfig` during `doUpgrade` and does not copy user properties,
   so the context is passed on the endpoint instance from `getEndpointInstance`, written into
   the rebuilt config from `modifyHandshake`, and left in the original config's user
   properties for containers that instantiate endpoints reflectively.
3. **The originating HTTP request is snapshotted before the protocol switch.** This was the
   largest finding. A servlet request is only valid for its dispatch, so every handler, which
   by definition runs later, saw a recycled request and failed. `WebSocketHandshakeRequest`
   copies the URI, headers, parameters, cookies, attributes, addresses and the secure flag
   while the upgrade is still being dispatched.
4. **The container bootstrap classes live in `servlet-websocket`**, not in the three server
   modules, each guarded by `@Requires(classes = ...)`. Users therefore add two dependencies
   and nothing else, and no server module gains a dependency on the WebSocket module.
5. **`@ExecuteOn` runs the handler inline on servlet containers by default.** Core only
   redispatches from a Reactor non-blocking thread, and a servlet request thread never is one,
   so the container thread is used directly, exactly as for HTTP controllers. That the
   executor selector really is consulted is proved by a spec that sets
   `micronaut.server.redispatch-non-blocking-only: false` and asserts the handler lands on the
   blocking executor.
6. **Broadcast failures are judged by session state, not exception type.** Containers report a
   vanished peer differently, Tomcat with `IllegalStateException`, so a send failure is
   ignored when the session is no longer open and propagated otherwise.
7. **The endpoint config path is the concrete request path**, which is this design's own
   stated fallback. Path parameters are passed explicitly, so no template is needed.
8. **Encoding and decoding use streams directly.** `ByteArrayByteBuffer.toOutputStream()`
   throws `IllegalStateException("Not implemented")` in core, so the message body writer
   writes to a `ByteArrayOutputStream` and the reader reads from a `ByteArrayInputStream`.
9. **An `@OnMessage(maxPayloadLength)` value only overrides the configured size when it
   differs from the annotation default**, since Micronaut cannot distinguish an explicit
   65536 from an unset value.

### Confirmed as designed

- `upgradeHttpToWebSocket` works from inside the Micronaut servlet on all three containers,
  synchronously on the dispatch thread, without entering async mode.
- Micronaut filters and security run on the handshake, and a filter response cancels the
  upgrade and is written as ordinary HTTP.
- Excluding `jetty-ee10-annotations` from `jetty-ee10-websocket-jakarta-server` is safe.
- Tomcat needs only `WsSci` wrapped as a bean; Undertow needs only the
  `WebSocketDeploymentInfo` servlet context attribute.

### Not done

- **The `websocket` TCK tag and the shared TCK tests in micronaut-core.** These are a change
  to `http-server-tck` in the core repository and belong in a core pull request, validated
  through `--include-build` first. The servlet-side suites are unchanged and still exclude
  nothing new.
- Kotlin `suspend` handler coverage, native-image metadata, and the WAR-mode 501 fallback
  test, which are phase 4.

---

## 0. Summary and the one decision that matters

Micronaut's `@ServerWebSocket` programming model can be run on all three servlet containers through a single, container-neutral mechanism: **Micronaut owns the upgrade request, the container owns the socket.**

1. The upgrade `GET` reaches `DefaultMicronautServlet` like any other request. Core's router already registers every `@ServerWebSocket` bean as a `GET` route (`ServerWebSocketProcessor`, verified in `http-server/.../websocket/ServerWebSocketProcessor.java:62-77`).
2. `ServletHttpHandler` detects the upgrade, runs the full Micronaut filter chain against it exactly as Netty's `NettyServerWebSocketUpgradeHandler` does (same `RequestLifecycle.runWithFilters` call, verified `RequestLifecycle.java:347`), so `micronaut-security`, session filters and user filters see the handshake and can reject it with a real HTTP response.
3. If the filters let it through, the engine calls `jakarta.websocket.server.ServerContainer.upgradeHttpToWebSocket(request, response, endpointConfig, pathParams)`. This method is part of Jakarta WebSocket 2.1 and is implemented by Jetty 12.1.10, Tomcat 11.0.23 and Undertow 2.3.25 (verified with `javap` on the three artifacts). The `ServerEndpointConfig` names a programmatic `jakarta.websocket.Endpoint` that adapts the container's `Session` to Micronaut's `WebSocketSession` and dispatches to the bean's `@OnOpen` / `@OnMessage` / `@OnClose` / `@OnError` executable methods.

No endpoint is ever registered with the container via `addEndpoint`, so the container's own upgrade filter (Jetty `WebSocketUpgradeFilter`, Tomcat `WsFilter`, Undertow `JsrWebSocketFilter`) never matches and never bypasses Micronaut. Path variables, filters, security, `@ExecuteOn`, events and route attributes all behave as on Netty because the same core classes are reused.

The alternative, registering a `ServerEndpointConfig` per bean with `addEndpoint` and letting the container do the upgrade, is rejected: the handshake would run before the Micronaut servlet, Micronaut filters and `@Secured` would not apply, and `ServerEndpointConfig.Configurator.modifyHandshake` has no portable way to reject a handshake with a status code. Section 1.10 spells out the comparison.

Recommended module layout: a new `servlet-websocket` module (`io.micronaut.servlet:micronaut-servlet-websocket`) plus one small bootstrap class per container module, with a thin upgrade hook in `servlet-core`. Jetty first. Estimated effort is four to five engineer-weeks including the shared TCK tests (section 6).

---

## 1. Mapping the Micronaut model onto Jakarta WebSocket

### 1.1 Discovery and routing (already portable, verified)

- `micronaut-websocket` provides `WebSocketBeanRegistry.forServer(beanContext)` → `WebSocketBean<T>` with `openMethod()`, `messageMethod()`, `pongMethod()`, `closeMethod()`, `errorMethod()` as `MethodExecutionHandle`s discovered from compile-time `ExecutableMethod`s (`DefaultWebSocketBeanRegistry.java:66-125`). An `@OnMessage` method is mandatory; a second `@OnMessage` whose arguments include `WebSocketPongMessage` is the pong handler. This is reused as-is.
- `micronaut-http-server` (already an `api` dependency of `servlet-core`) contains `ServerWebSocketProcessor`, active only when `micronaut-websocket` is on the classpath. It registers `GET <uri>` for every bean's `@OnMessage`/`@OnOpen` method, and `DefaultRouteInfo.isWebSocketRoute()` is true for those routes (`router/.../DefaultRouteInfo.java:117`). `RequestLifecycle` already answers a plain `GET` to such a route with HTTP 400 `NotWebSocketRequestException` (`RequestLifecycle.java:399-405`). Nothing to build here.
- Consequence: today, merely adding `micronaut-websocket` to a servlet application makes `@ServerWebSocket` routes 400 instead of 404. The new module turns that into a working upgrade.

### 1.2 Upgrade detection and the request lifecycle

New in `servlet-core` (no Jakarta WebSocket dependency, so JDK and POJA runtimes keep compiling):

- `ServletHttpHandler.service(exchange)` gains an early branch: if the request is `GET`, carries `Connection: upgrade` and `Upgrade: websocket` (same header test as `NettyServerWebSocketUpgradeHandler.isWebSocketUpgrade`, `:133`), and a `ServletWebSocketUpgrader<REQ,RES>` bean is present, the request is handled by a new `WebSocketUpgradeRequestLifecycle` instead of `normalFlow`.
- `WebSocketUpgradeRequestLifecycle extends RequestLifecycle` is a port of Netty's private `WebsocketRequestLifecycle` (`NettyServerWebSocketUpgradeHandler.java:337-372`): find the route with `router.find(GET, path, request)` filtered on `isWebSocketRoute()`, set the route attributes on the request, run `runWithFilters(request, (r, ctx) -> ExecutionFlow.just(proceed))` with a sentinel `HttpResponse.ok()`; on route miss run `onError(request, new HttpStatusException(NOT_FOUND, "WebSocket Not Found"))` so filters still run for unmatched upgrades (Netty asserts this in `SimpleTextWebSocketSpec`).
- The upgrade proceeds only if the filter chain returns the identical sentinel instance. Any other response (401 from `SecurityFilter`, a redirect, a custom filter response) is written to the client through the existing `transfer()` path and the connection stays HTTP.
- **Threading constraint (verified):** the branch runs on the container's dispatch thread and blocks on `flow.toCompletableFuture().get()`, the way the existing `async-supported=false` path does, and it does **not** call `startAsync()`. Undertow's `ServletWebSocketHttpExchange` requires the thread-local `ServletRequestContext` of the dispatching thread (`SecurityActions.requireCurrentServletRequestContext`, verified in bytecode), and Tomcat's `Request.upgrade` drives the coyote `UPGRADE` action on the same request. Whether Tomcat and Jetty also tolerate an upgrade after `startAsync()` is **to verify in the spike**; the design does not rely on it. Filters that do blocking work therefore block a container thread, which on Jetty and Tomcat is a virtual thread when `micronaut.servlet.enable-virtual-threads` is true.
- `ServletWebSocketUpgrader<REQ,RES>` is a new SPI in `servlet-core`: `void upgrade(ServletExchange<REQ,RES> exchange, UriRouteMatch<?,?> routeMatch, MutableHttpResponse<?> handshakeResponse)`. `servlet-websocket` provides the Jakarta implementation. Without the bean, behaviour is unchanged.

### 1.3 `ServerEndpointConfig` and the programmatic `Endpoint`

Per `@ServerWebSocket` bean the `servlet-websocket` module builds one `ServerEndpointConfig` (lazily, cached per bean type):

| `ServerEndpointConfig` member | Value |
|---|---|
| `getEndpointClass()` | `MicronautServerEndpoint` (a `jakarta.websocket.Endpoint` subclass owned by the module) |
| `getPath()` | A Jakarta-safe template derived from the route's `UriMatchTemplate`: `{name}` variables kept, Micronaut-only syntax (`{?q}`, `{+path}`, regex) removed. The container is never asked to match this path; it is an identifier. **To verify in the spike:** Jetty's `validateEndpointConfig` and Undertow's `PathTemplate.create(path)` accept the derived form. Fallback is to pass the concrete request path. |
| `getSubprotocols()` | Split of `@ServerWebSocket(subprotocols = "a,b")` (CSV, verified `ServerWebSocket.java:72`) |
| `getExtensions()` | Container defaults, filtered by the compression setting (section 4) |
| `getEncoders()` / `getDecoders()` | Empty. Encoding and decoding stay in Micronaut (section 1.5) so that `MessageBodyHandlerRegistry` and Serde work unchanged. |
| `getConfigurator()` | `MicronautEndpointConfigurator`: `getEndpointInstance` returns a new `MicronautServerEndpoint` carrying the `WebSocketBean`, the originating `HttpRequest`, the `UriRouteMatch` and the handshake attributes; `getNegotiatedSubprotocol` uses the container default (first client-requested protocol that the bean lists); `checkOrigin` returns true (CORS and origin policy are a Micronaut filter concern, as on Netty); `getNegotiatedExtensions` returns an empty list when compression is disabled; `modifyHandshake` copies any headers the filter chain set on the sentinel response onto the `HandshakeResponse`. |
| `getUserProperties()` | Per-upgrade map holding the originating request, route match and the Micronaut session once created |

Path parameters are passed explicitly to `upgradeHttpToWebSocket` as `Map<String,String>` from `routeMatch.getVariableValues()`, so `Session.getPathParameters()` is populated on all three containers without relying on their template matching. Micronaut's `WebSocketSession.getUriVariables()` never consults the container.

A `ServerEndpointConfig` is built per upgrade and its configurator carries the per-connection context, because `getEndpointInstance` receives no config of its own.

### 1.4 `@OnOpen`

`MicronautServerEndpoint.onOpen(Session, EndpointConfig)`:

1. Creates `ServletWebSocketSession` (section 1.7) and registers it in `ServletWebSocketSessionRegistry` (section 1.8).
2. Applies per-session limits: `@OnMessage(maxPayloadLength)` → `session.setMaxTextMessageBufferSize` and `setMaxBinaryMessageBufferSize`; configured idle timeout → `setMaxIdleTimeout`.
3. Registers message handlers (section 1.5).
4. Binds and invokes the `@OnOpen` method through `DefaultExecutableBinder` + `WebSocketStateBinderRegistry` with a `WebSocketState(session, originatingRequest)`, exactly as `AbstractNettyWebSocketHandler.callOpenMethod` (`:158-190`). Argument order is irrelevant; `WebSocketSession` binds by type, everything else (`HttpRequest`, `HttpHeaders`, `@Header`, `@QueryValue`, `@PathVariable`, `@CookieValue`, `@RequestAttribute`, `Principal`) binds through the ordinary `RequestBinderRegistry` against the originating request, and unmatched names fall back to URI variables then query parameters (verified `WebSocketStateBinderRegistry.java:63-86`).
5. Runs the invocation through the executor selected for the method (section 1.9) and publishes `WebSocketSessionOpenEvent`.
6. A binding failure or thrown exception closes the session with `CloseReason.INTERNAL_ERROR` (1011) after offering it to `@OnError`, matching Netty.

`WebSocketStateBinderRegistry` and `WebSocketState` are `@Internal` in core. The module uses them anyway (Netty is the only other consumer). If core maintainers prefer, the two classes are about 120 lines and can be copied; this should be decided during review of the first PR.

### 1.5 `@OnMessage`: text, binary and pong

Micronaut has one `@OnMessage` method whose body argument is "the single argument the binder cannot satisfy", computed once per connection (`NettyServerWebSocketHandler.java:151-171`). Jakarta allows one handler per message class per session. The endpoint therefore registers:

- `MessageHandler.Whole<String>` and `MessageHandler.Whole<ByteBuffer>` if a message method exists, both funnelling into the same dispatch;
- `MessageHandler.Whole<PongMessage>` if a pong method exists, wrapping `PongMessage.getApplicationData()` as `WebSocketPongMessage(ByteArrayBufferFactory.INSTANCE.wrap(bytes))`.

Decoding order mirrors Netty's, minus its fragment bug:

1. `ConversionService.convert(payload, bodyArgument)` where payload is the `String` or the `byte[]` copy of the `ByteBuffer`. This satisfies `String`, `byte[]`, `java.nio.ByteBuffer`, Micronaut `ByteBuffer<?>`, `CharSequence`, primitives.
2. Otherwise `MessageBodyHandlerRegistry.findReader(bodyArgument, mediaType)` with the media type from `@Consumes` on the method, default `application/json`, reading from the whole payload. `servlet-core` already holds a `MessageBodyHandlerRegistry`; the deprecated `MediaTypeCodecRegistry` path Netty still has is not ported.
3. Nothing applicable → close with `UNSUPPORTED_DATA` (1003), same message text as Netty.

A text message arriving at a bean whose body argument is `byte[]` is converted; a binary message arriving at a `String` body is decoded as UTF-8. Unlike Jakarta's own POJO model there is no notion of "text-only" or "binary-only" endpoints, which keeps parity with Netty.

**Partial messages.** Micronaut's model only ever delivers whole messages: Netty aggregates continuation frames by hand (`AbstractNettyWebSocketHandler.java:340-358`). The servlet implementation therefore registers only `Whole` handlers and lets the container aggregate. The container enforces the maximum message size and closes with `TOO_BIG` (1009) when exceeded. Semantics differ from Netty in one respect and it is documented: `maxPayloadLength` on Netty bounds a single frame, on servlet it bounds the whole message. Exposing `MessageHandler.Partial` (for example when the body argument is a `Publisher<byte[]>`) is a possible later extension and is not part of this design.

Inbound ping frames are answered by the container automatically on all three implementations, as Netty does (`:424`); there is no user hook, as today.

### 1.6 Return values, `@OnClose`, `@OnError`

- **Return values are awaited, never sent** (verified `NettyServerWebSocketHandler.invokeExecutable0` `:375-392`). `Publisher` results are subscribed, `CompletionStage` results awaited, plain values ignored; completion publishes `WebSocketMessageProcessedEvent`; failure goes to `@OnError` or, without one, closes with 1011. A user echoes by returning `session.send(...)` or `broadcaster.broadcast(...)`. The servlet module reproduces this with `ExecutionFlow` / `ReactiveExecutionFlow`, which are already used by `ServletHttpHandler`.
- **`@OnClose`**: `Endpoint.onClose(Session, jakarta.websocket.CloseReason)` maps to Micronaut `CloseReason(code, reasonPhrase)`, pre-binds it by type at whatever parameter position it appears (Netty `:520-525`), binds the rest (`WebSocketSession`, path variables, request bindings), invokes once (guarded by an `AtomicBoolean`), publishes `WebSocketSessionClosedEvent`, removes the session from the registry. Idle timeouts close with 1001 `GOING_AWAY` on all three containers, which is the code Netty uses for idle (`:234-240`), so `WebSocketErrorsSpec`'s timeout assertion ports unchanged.
- **`@OnError`**: `Endpoint.onError(Session, Throwable)` and any exception from a handler invocation go to `@OnError` with the `Throwable` pre-bound by type; without a handler, `IOException`s whose message contains "Connection reset" are swallowed and everything else closes with 1011 (Netty `:591-606`). `@OnError` does not auto-close, as on Netty.

### 1.7 `WebSocketSession` over `jakarta.websocket.Session`

`ServletWebSocketSession implements WebSocketSession` (in `servlet-websocket`):

| `WebSocketSession` member | Implementation |
|---|---|
| `getId()` | `Session.getId()` (opaque; Netty uses `Sec-WebSocket-Key`. Documented as opaque.) |
| `getAttributes()` / `MutableConvertibleValues` methods | `MutableConvertibleValuesMap`, seeded from the originating request's `micronaut.SESSION` attribute when present, mirroring `NettyWebSocketSession.java:95` so `micronaut-session` carry-over works |
| `isOpen()` | `Session.isOpen()` |
| `isWritable()` | `true` while the per-session send queue is below its limit (see below) |
| `isSecure()` | `Session.isSecure()` |
| `getOpenSessions()` | All open Micronaut sessions from the registry, global across endpoints like Netty's `ChannelGroup` (Jakarta's `Session.getOpenSessions()` is per endpoint and is not used) |
| `getRequestURI()`, `getRequestParameters()` | Originating `HttpRequest` |
| `getProtocolVersion()` | `Session.getProtocolVersion()` |
| `getSubprotocol()` | `Session.getNegotiatedSubprotocol()` |
| `getUriVariables()` | `routeMatch.getVariableValues()` |
| `getUserPrincipal()` | `HttpAttributes.PRINCIPAL` request attribute (set by micronaut-security), else `Session.getUserPrincipal()` |
| `sendAsync(msg, mediaType)` | Encode (below), then `getAsyncRemote().sendText/sendBinary(payload, SendHandler)`, completing a `CompletableFuture` from the `SendResult`. Closed session → `WebSocketSessionException("Session closed")` |
| `sendSync(msg, mediaType)` | `sendAsync(...).get()` (interface default), which keeps a single write path; a direct `getBasicRemote()` variant is not used because Basic and Async remotes must not be mixed concurrently on Tomcat |
| `send(msg, mediaType)` | Cold `Mono`-style publisher that calls `sendAsync` on subscribe and emits the message (Reactor is already a dependency of `servlet-core`) |
| `sendPingAsync(byte[])` | `RemoteEndpoint.sendPing(ByteBuffer)` is blocking on the Jakarta API; run on the `BLOCKING` executor and complete a future. This is an upgrade over the interface default, which throws `UnsupportedOperationException` |
| `close()` / `close(CloseReason)` | `Session.close(new jakarta.websocket.CloseReason(CloseCodes.getCloseCode(code), reason))`; unregisters from the registry |

**Send serialization (design detail that Netty gets for free).** Jakarta containers reject a second async send while one is in flight (Tomcat throws `IllegalStateException` "invalid state for called method"). Micronaut code sends from arbitrary threads and the broadcaster fans out concurrently, so `ServletWebSocketSession` keeps a per-session FIFO queue with one outstanding container send; `SendHandler` completion drains the next entry. `isWritable()` reports whether the queue is under `micronaut.servlet.websocket.max-pending-sends` (default 64); above that `sendAsync` still enqueues, matching Netty's unbounded channel outbound buffer, but `isWritable()` lets callers back off.

**Encoding** (`ServletWebSocketMessageEncoder`, the logic of `WebSocketMessageEncoder` minus Netty frames, verified `http-netty/.../WebSocketMessageEncoder.java:82-114`): `byte[]`, `java.nio.ByteBuffer`, Micronaut `ByteBuffer<?>` → binary; `CharSequence` and `java.lang` types → text; everything else → `MessageBodyHandlerRegistry.findWriter(type, mediaType)` (default JSON) → **text**, as on Netty. Unencodable → `WebSocketSessionException`.

### 1.8 `WebSocketBroadcaster` and session repository

- Core's `WebSocketSessionRepository` lives in `micronaut-http-netty` and is typed on `io.netty.channel.Channel` / `ChannelGroup` (verified). It cannot be reused. `servlet-websocket` adds `ServletWebSocketSessionRegistry` (`@Singleton`, concurrent set of `ServletWebSocketSession`), which closes all sessions with 1001 on `ServerShutdownEvent` the way `NettyHttpServer` closes its channel group on stop.
- `ServletWebSocketBroadcaster implements WebSocketBroadcaster`, `@Requires(beans = ServletWebSocketSessionRegistry.class)`: encode once, filter open sessions by the predicate, enqueue on each session, aggregate failures ignoring closed-session `IOException`s the way Netty ignores `ClosedChannelException` (`NettyServerWebSocketBroadcaster.java:111-130`). Because both Netty and servlet broadcasters are conditional on their own repository bean, they never coexist.

### 1.9 `@ExecuteOn`, virtual threads and container threads

Netty resolves the executor per invocation through `ExecutorSelector.selectExecutor(method, threadSelection)` from `RouteExecutor.getExecutorSelector()` and `HttpServerConfiguration.getThreadSelection()` (verified `NettyServerWebSocketHandler.java:375-380`, `WebsocketExecuteOnSpec`). The servlet endpoint does the same for `@OnOpen`, `@OnMessage`, `@OnClose` and `@OnError`, so `@ExecuteOn` on the class or the method works identically, and the `BLOCKING` executor is virtual-thread backed when Loom is available.

Without `@ExecuteOn` (the default `MANUAL` thread selection) handlers run on the thread the container calls the `MessageHandler` on:

| Container | Thread | Note |
|---|---|---|
| Jetty | `QueuedThreadPool`; virtual when `enable-virtual-threads` (verified `JettyFactory.java:452-456`) | Same executor as `TaskExecutors.BLOCKING` |
| Tomcat | Connector executor; `VirtualThreadExecutor` when enabled (verified `TomcatVirtualThreadEnabler`) | |
| Undertow | XNIO **I/O thread** unless `WebSocketDeploymentInfo.setDispatchToWorkerThread(true)` | The bootstrap sets it to true. Undertow has no virtual-thread support today (parity plan WS5); users wanting virtual threads use `@ExecuteOn(BLOCKING)` |

Message ordering: as on Netty, a handler that dispatches to another executor may be invoked concurrently for consecutive messages. Documented, unchanged.

Kotlin `suspend` handlers: Netty uses `RouteExecutor.getCoroutineHelper()` and `ContinuationArgumentBinder` (`:347-373`); the same code is ported in phase 4 and covered by the Kotlin test suite.

### 1.10 Filters and security on the upgrade request

What the recommended design gives, uniformly on Jetty, Tomcat and Undertow:

| Behaviour | Netty | Servlet (this design) |
|---|---|---|
| `@ServerFilter` / `HttpServerFilter` / `@RequestFilter` run on the handshake | yes | yes, via the same `runWithFilters` |
| `micronaut-security` `SecurityFilter`, `@Secured` on the `@ServerWebSocket` class | yes | yes, rejection is written as the filter's HTTP response |
| Filter substitutes a response → no upgrade | yes | yes |
| Filters run for upgrade requests that match no route | yes | yes |
| Headers set by filters appear on the `101` response | yes | yes, via `Configurator.modifyHandshake` and by setting them on the `HttpServletResponse` before the upgrade call (**to verify in the spike** that all three containers preserve pre-set headers) |
| Route match visible in request attributes (`WebsocketRouteMatchSpec`) | yes | yes |
| `micronaut-session` attribute carry-over | yes | yes |
| Jakarta `Filter` beans (`@ServletFilterBean`) | n/a | run before the Micronaut servlet, therefore before Micronaut filters, exactly as for HTTP (documented limitation already in `servletAnnotation.adoc`) |
| Response-phase filter logic sees the `101` | no (sees the sentinel 200) | no (same sentinel) |

What is **not** possible with the rejected alternative (container-registered endpoints): none of the first four rows, on any container. `ServerEndpointConfig.Configurator.modifyHandshake` cannot set a status; Tomcat treats an exception thrown there as a 500 with a container-formatted body, Jetty and Undertow differ again. That inconsistency is the main reason for the recommended design.

The container's own upgrade filter still exists on `/*` after bootstrap (Tomcat and Jetty add one in their initializer, Undertow in `Bootstrap`). With no registered endpoints it is a pass-through (verified: `WsFilter.doFilter` checks `areEndpointsRegistered()` and `findMapping`). Applications that additionally register their own Jakarta endpoints through the `ServerContainer` keep working, without Micronaut filters, which the docs will state.

---

## 2. Per-container registration

The engine never needs a container-specific type at upgrade time: it reads the `ServerContainer` from `ServletContext.getAttribute("jakarta.websocket.server.ServerContainer")`, the attribute name mandated by the specification and set by all three implementations (verified: Tomcat `WsSci.init`, Jetty `JakartaWebSocketServerContainer.ensureContainer`, Undertow `Bootstrap.handleDeployment`). The `ServletContext` is already available to the Micronaut servlet as request attribute `ServletAttributes.SERVLET_CONTEXT`. If the attribute is missing, the engine logs once and answers the upgrade with 501 and a clear message.

What each container needs so that the attribute exists in embedded mode:

### 2.1 Jetty (`http-server-jetty`)

- Artifact: `org.eclipse.jetty.ee10.websocket:jetty-ee10-websocket-jakarta-server:12.1.10`, **excluding** `org.eclipse.jetty.ee10:jetty-ee10-annotations`. Verified: the only class in the artifact that references the annotations, webapp or ASM packages is `JakartaWebSocketConfiguration`, a `WebAppContext` configuration service that embedded mode never loads. Without the exclusion the dependency drags in `jetty-ee10-annotations`, `jetty-ee10-plus`, `jetty-jndi`, `jakarta.enterprise.cdi-api` and three ASM jars. With it the footprint is seven Jetty websocket jars plus the two `jakarta.websocket` API jars.
- Bootstrap: a `JettyWebSocketInitializer implements ServletContainerInitializer` bean, `@Requires(classes = JakartaWebSocketServletContainerInitializer.class)` and `@Requires(property = "micronaut.servlet.websocket.enabled", notEquals = "false")`. Its `onStartup` calls `JakartaWebSocketServletContainerInitializer.initialize(ServletContextHandler.getServletContextHandler(ctx))`, which ensures `WebSocketServerComponents`, `WebSocketMappings`, the `WebSocketUpgradeFilter` and the `JakartaWebSocketServerContainer` on the context (verified in bytecode), then applies the defaults from section 4 on the returned `ServerContainer`. `JettyFactory` picks it up through the existing `applicationContext.getBeansOfType(ServletContainerInitializer.class)` seam (`JettyFactory.java:126`, `:385`); no factory change is required.
- `JettyFactory.newJettyContext` creates a plain `ServletContextHandler`; that is the type the Jetty initializer requires. Sessions and security are off on it today and remain so.

### 2.2 Tomcat (`http-server-tomcat`)

- Artifact: `org.apache.tomcat.embed:tomcat-embed-websocket:11.0.23` (depends only on `tomcat-embed-core`; ~320 KB).
- `Tomcat.addContext` (used by `TomcatFactory.newTomcatContext`) attaches only `FixContextListener`, not `ContextConfig` (verified in bytecode), so the `META-INF/services` registration of `WsSci` is never scanned. Bootstrap: a `TomcatWebSocketInitializer` bean wrapping `new WsSci()`, guarded by `@Requires(classes = WsSci.class)`. `TomcatFactory.configureServletInitializer` already registers non-Micronaut initializers with `Set.of()` as the handled-types set (`TomcatFactory.java:179-181`); with an empty set `WsSci.onStartup` simply calls `init(ctx, true)`, creating the `WsServerContainer`, setting the attribute and adding `WsFilter`, `WsSessionListener` and `WsContextListener` (verified). No factory change.
- Buffer sizes: `WsServerContainer` reads the context init parameters `org.apache.tomcat.websocket.textBufferSize` / `binaryBufferSize` at construction; the engine instead sets `setDefaultMaxTextMessageBufferSize` / `Binary` on the returned `ServerContainer` after `init`, which is portable. The initializer must run after `WsSci`, so it wraps it rather than being a sibling.

### 2.3 Undertow (`http-server-undertow`)

- Artifact: `io.undertow:undertow-websockets-jsr:2.3.25.Final` (brings `jakarta.websocket-api` 2.1.0 and `jakarta.websocket-client-api` 2.1.0; Jetty brings 2.1.1, the BOM should manage `jakarta.websocket-api` to 2.1.1).
- `io.undertow.websockets.jsr.Bootstrap` is a `ServletExtension` loaded by `DeploymentManagerImpl.deploy()` through `ServiceLoader` (verified), but it only acts when `DeploymentInfo.getServletContextAttributes()` contains `WebSocketDeploymentInfo.ATTRIBUTE_NAME` (verified). Bootstrap: an `UndertowWebSocketDeploymentCustomizer` bean (`BeanCreatedEventListener<DeploymentInfo>`, `@Requires(classes = WebSocketDeploymentInfo.class)`) that adds `deploymentInfo.addServletContextAttribute(WebSocketDeploymentInfo.ATTRIBUTE_NAME, new WebSocketDeploymentInfo().setDispatchToWorkerThread(true).addExtension(new PerMessageDeflateHandshake()) /* when compression enabled */)`. Buffers and worker are left unset: `Bootstrap` falls back to the default container buffer pool, and the servlet upgrade path takes the worker from the exchange. **To verify in the spike** on 2.3.25.
- The `ServerContainer` attribute is set by `Bootstrap` during deployment, before `MicronautServletInitializer.onStartup`. Defaults from section 4 are applied by the customizer through `WebSocketDeploymentInfo.addListener(ContainerReadyListener)`.

### 2.4 Interaction with `MicronautServletInitializer`

Unchanged. It does not scan `@HandlesTypes` and does not need to: no Jakarta endpoint is registered at startup. The websocket bootstrap beans are independent `ServletContainerInitializer` (Jetty, Tomcat) or `DeploymentInfo` (Undertow) contributions and their order relative to the Micronaut initializer does not matter. The Micronaut servlet keeps `asyncSupported` as configured; the upgrade branch simply does not enter async mode for that one request.

### 2.5 WAR deployment

Supported without extra code when the hosting container implements Jakarta WebSocket **2.1** (Tomcat 10.1+, Jetty 12 EE10 with the `ee10-websocket-jakarta` module enabled, WildFly 27+/Undertow 2.3, Payara 7). In a WAR the container's own initializer publishes the `ServerContainer` attribute before `MicronautServletInitializer` runs, and the engine's upgrade path is container-neutral. The `http-server-*` bootstrap beans are inactive in a WAR because their factories are.

On a Jakarta WebSocket **2.0** container (Tomcat 10.0, Jetty 11) `upgradeHttpToWebSocket` does not exist: the engine detects the missing method reflectively at startup, logs a warning and answers upgrades with 501. Container-proprietary fallbacks (`WsServerContainer.doUpgrade`, `JettyWebSocketServerContainer.upgrade`) are deliberately not used. Recorded in `knownIssues.adoc`.

---

## 3. Client side for tests

- `micronaut-http-client` (Netty) is already on every servlet module's and every TCK module's test classpath (verified: the `implementation` and `http-server-tck-module` convention plugins both add it), and it is the only `WebSocketClientFactory` implementation in core (`META-INF/services` lists just `NettyHttpClientFactory`; `micronaut-http-client-jdk` has no WebSocket support). Netty on the test classpath coexists with the servlet server today, so it is sufficient for all server-side tests, and `@ClientWebSocket` beans from the Netty specs port unchanged.
- For assertions the Netty client cannot make (negotiated extensions, negotiated subprotocol as the container sees it), tests use the container's own Jakarta client: `ContainerProvider.getWebSocketContainer()` resolves Tomcat's `WsWebSocketContainer`, Jetty's `JakartaWebSocketClientContainer` or Undertow's `UndertowContainerProvider`, all shipped in the artifacts above. `Session.getNegotiatedExtensions()` then proves permessage-deflate is on or off.
- A JDK `java.net.http.WebSocket`-based `WebSocketClientFactory` is feasible against the core SPI (only `createWebSocketClient(URI[, HttpClientConfiguration])` needs implementing) but would re-implement the ~600 lines of binding and aggregation that `AbstractNettyWebSocketHandler` holds, has no permessage-deflate, and `WebSocketClientFactoryResolver` picks the first `ServiceLoader` provider with no priority. It is **not** needed for this project and is out of scope; if a Netty-free client is wanted for applications it belongs in core as a separate proposal.

---

## 4. Configuration surface

Core has **no** `micronaut.server.websocket.*` namespace (verified: no `WebSocketConfiguration` class, no websocket properties in `HttpServerConfiguration` or `NettyHttpServerConfiguration`). Netty's knobs are the annotations, `micronaut.server.idle-timeout`, and always-on server compression. This design therefore introduces `micronaut.servlet.websocket.*` in `servlet-websocket` (`ServletWebSocketConfiguration`, `@ConfigurationProperties("micronaut.servlet.websocket")`), applied through the portable `WebSocketContainer` setters where possible:

| Property | Default | Applied via | Netty equivalent |
|---|---|---|---|
| `enabled` | `true` | Bootstrap beans and upgrader bean `@Requires` | n/a |
| `max-text-message-size` | `65536` | `ServerContainer.setDefaultMaxTextMessageBufferSize`; overridden per session by `@OnMessage(maxPayloadLength)` | `@OnMessage(maxPayloadLength)` (per frame) |
| `max-binary-message-size` | `65536` | `setDefaultMaxBinaryMessageBufferSize`; same override | same |
| `idle-timeout` | inherits `micronaut.server.idle-timeout` (core default 5 minutes) | `setDefaultMaxSessionIdleTimeout` and `Session.setMaxIdleTimeout`; closes with 1001 | `IdleStateHandler` → 1001 |
| `async-send-timeout` | container default | `setAsyncSendTimeout` | n/a |
| `max-pending-sends` | `64` | `isWritable()` threshold (section 1.7) | channel writability |
| `compression.enabled` | `true` | `Configurator.getNegotiatedExtensions` returns empty when false (Jetty, Tomcat); Undertow additionally adds `PerMessageDeflateHandshake` only when true | always on |
| `max-frame-size` | not exposed | Jetty only (`WebSocketConstants.DEFAULT_MAX_FRAME_SIZE` 65536); Tomcat and Undertow have no per-frame limit in the public API | per frame 65536 |

Container defaults differ (Jetty 64 KiB messages and 30 s idle, Tomcat 8 KiB buffers and no idle timeout, Undertow no message limit), so the module always sets the three size and timeout values explicitly to make behaviour identical across containers and equal to Netty's numbers. Whether the shared keys should later move to a core-level `micronaut.server.websocket.*` namespace is a question for core maintainers; nothing here blocks that migration since the property names would be aliased.

---

## 5. Testing

### 5.1 What ports from the Netty suite

Verified per spec (`http-server-netty/src/test/groovy/io/micronaut/http/server/netty/websocket/**` and `.../websocket/**`):

| Spec | Ports verbatim? | Notes |
|---|---|---|
| `SimpleTextWebSocketSpec` (text exchange, SSL, query-parameter binding, filter cancels upgrade, filters run on unmatched upgrade) | yes | public `WebSocketClient` + `@ClientWebSocket` only |
| `PojoWebSocketSpec` | yes | JSON POJO round trip |
| `WebSocketErrorsSpec` (idle timeout → `@OnClose` 1001, throwing handler without/with `@OnError`) | yes | property `micronaut.server.idle-timeout: 5s` is honoured through section 4 |
| `WebsocketExecuteOnSpec` (`@ExecuteOn(BLOCKING)` on open/message/error, sync/reactive/async) | yes | |
| `WebsocketRouteMatchSpec` | yes | |
| `test-suite` / `test-suite-groovy` doc specs, `test-suite-kotlin` `WebSocketSuspendTest` | yes | Kotlin one after phase 4 |
| `BinaryWebSocketSpec` | partly | binary exchange, multi-frame message and ping/pong port with the Netty client; the compression assertions hook Netty pipelines and are rewritten against the container's Jakarta client (section 3) |
| `BroadcasterSpec` (client closes mid-broadcast) | rewrite | raw Netty bootstrap client; rewrite with the JDK `java.net.http.WebSocket` client as the misbehaving peer |
| `WebSocketSpec` (close during pending filter, `EmbeddedChannel`) | rewrite | becomes a test that a slow filter plus client disconnect leaves no session registered |
| `UpgradeSpec` (header matrix for `isWebSocketUpgrade`) | port as a unit test of the servlet-core detection method | |

### 5.2 Shared suite and the `websocket` tag

The core HTTP Server TCK has no WebSocket tests (verified) and one tag, `multipart`, used with `@ExcludeTags` in core's JDK suite. The five servlet suites use `@ExcludeClassNamePatterns` only. The parity plan (WS0/WS7) is already converting exclusions to tags and names `websocket` as the tag the servlet suites will exclude; this design supplies it:

1. Add `io.micronaut.http.server.tck.tests.websocket.*` to `http-server-tck` in core, JUnit 5 ports of the "yes" rows above using `WebSocketClient` from `micronaut-http-client`, every class annotated `@Tag("websocket")`. The TCK needs `micronaut-websocket` and `micronaut-http-client` as dependencies and a `ServerUnderTest` accessor for the server URI (**to verify**: `ServerUnderTest.getPort()` / `getURL()` exist in 5.2.x; the servlet suites use `EmbeddedServerUnderTestProvider`, which has the `EmbeddedServer`).
2. Core's Netty suite runs them by default. `test-suite-http-server-tck-jdk` and `test-suite-http-server-tck-poja-apache` add `@ExcludeTags("websocket")`. The Jetty, Tomcat and Undertow suites add `micronaut-servlet-websocket` plus the container websocket artifact to their test classpath and run the tag as each phase lands (until then they exclude it).
3. This follows the memory'd rule for core PRs: validate with `--include-build` against the `servlet-parity-tck` core worktree first, then open the core PR. Coordinate with that worktree rather than editing it.

Servlet-only mechanics (bootstrap, configuration, `isWritable`, send queue, WAR-mode 501 fallback, container defaults) are Spock specs in each `http-server-*` module under `.../websocket/`, plus unit tests in `servlet-websocket`.

### 5.3 Native image

The TCK modules already run `nativeTest` with the GraalVM reachability metadata repository enabled, so the `websocket`-tagged tests become native tests for free once included. Expected metadata work: the container websocket implementations use `ServiceLoader` (`ContainerProvider`, `ServerEndpointConfig$Configurator`, Jetty `Extension`, Undertow `ServletExtension`) and reflective endpoint instantiation. Our endpoint is created by our configurator (no reflection), but the container services need `resource-config` entries and `@TypeHint`s (`WsSci`, `WsServerContainer`, Jetty's `JakartaWebSocketServletContainerInitializer`, Undertow's `Bootstrap`). Whether the reachability metadata repository already covers `tomcat-embed-websocket`, `jetty-ee10-websocket-jakarta-server` and `undertow-websockets-jsr` is **to verify** in phase 1; the repository currently ships only two `LocalStrings` bundles for the servlet engine.

---

## 6. Module layout, dependencies, plan

### 6.1 Layout

```
servlet-core           + WebSocketUpgradeRequestLifecycle, ServletWebSocketUpgrader SPI,
                         upgrade detection in ServletHttpHandler (no new dependency)
servlet-websocket      NEW  io.micronaut.servlet:micronaut-servlet-websocket
                         api  micronaut-servlet-engine, micronaut-websocket, jakarta.websocket-api (managed 2.1.1)
                         JakartaWebSocketUpgrader, MicronautServerEndpoint, MicronautEndpointConfigurator,
                         ServletWebSocketSession (+ send queue), ServletWebSocketMessageEncoder/Decoder,
                         ServletWebSocketSessionRegistry, ServletWebSocketBroadcaster,
                         ServletWebSocketConfiguration
http-server-jetty      + JettyWebSocketInitializer        compileOnly jetty-ee10-websocket-jakarta-server (minus annotations)
http-server-tomcat     + TomcatWebSocketInitializer       compileOnly tomcat-embed-websocket
http-server-undertow   + UndertowWebSocketDeploymentCustomizer   compileOnly undertow-websockets-jsr
servlet-bom            manages the four new artifacts (managed-* entries in gradle/libs.versions.toml)
docs                   src/main/docs/guide/websocket.adoc (+ toc entry after servletAnnotation), knownIssues.adoc
```

A separate module rather than code in `servlet-engine` because: it keeps `micronaut-websocket` and `jakarta.websocket-api` off the default classpath (adding `micronaut-websocket` alone changes routing of `@ServerWebSocket` beans to 400, section 1.1); the engine stays usable by the JDK and POJA runtimes without conditional classes; and the module is the natural home for the future Gradle/Maven plugin feature. Container artifacts stay `compileOnly` in the server modules so that users opt in with two dependencies (`micronaut-servlet-websocket` and, for example, `tomcat-embed-websocket`), mirroring how `logback-access-jetty12` is handled today. Making them `implementation` is a one-line change if maintainers prefer zero-config.

Java baseline: this repository compiles for Java 25 (`servlet.module.gradle`), so `java.net.http.WebSocket` and virtual threads are always available in tests.

### 6.2 Phases, estimates, risks

Jetty goes first. Its `JakartaWebSocketServletContainerInitializer.initialize(ServletContextHandler)` is an explicit, self-contained embedded API; the `ServletContextHandler` the factory creates is exactly the type it wants; Jetty's virtual-thread executor is already Micronaut's `BLOCKING` executor; its defaults are public constants; the Jetty TCK suite is the one already proven through `--include-build` in the parity work; and Jetty is the only container whose native-image support the docs currently claim. Tomcat is second because the `WsSci` bootstrap is a one-liner but `Request.upgrade` from within the Micronaut servlet needs the spike's confirmation. Undertow is last because it has the most bootstrap surface (`WebSocketDeploymentInfo`, dispatch mode, extension registration, buffer pool) and no virtual threads.

| Phase | Content | Estimate | Exit criterion |
|---|---|---|---|
| 0 Spike | Hard-coded `Endpoint` upgraded from inside `DefaultMicronautServlet` via `upgradeHttpToWebSocket` on all three containers; confirm sync-thread requirement, pre-set response headers, path-template acceptance, Jetty exclusion, Undertow null worker/buffers | 2–3 days | A text echo works on Jetty, Tomcat, Undertow from the same servlet code; findings folded into this document |
| 1 Jetty | `servlet-core` hook and lifecycle; `servlet-websocket` module complete (session, encoder, registry, broadcaster, configuration, events, `@ExecuteOn`); Jetty bootstrap; ported Spock specs; TCK `websocket` tests in the core worktree and tag wiring; docs | 1.5–2 weeks | Jetty TCK suite green with the `websocket` tag included; ported Netty specs green |
| 2 Tomcat | `WsSci` bootstrap, buffer defaults, virtual-thread check, TCK inclusion | 3–4 days | Tomcat TCK suite green with the tag |
| 3 Undertow | `WebSocketDeploymentInfo` customizer, deflate extension, worker dispatch, idle timeout, TCK inclusion | 4–5 days | Undertow TCK suite green with the tag |
| 4 Hardening | Kotlin `suspend`, WAR-mode 501 fallback test, native-image metadata and `nativeTest` on the three TCK modules, broadcaster edge cases, `knownIssues.adoc`, guide section | 1 week | `nativeTest` green on Jetty at minimum; docs published |

Total: four to five engineer-weeks, with phase 1 being the only one on the critical path for the others.

Risks, in order of likelihood:

1. **Container refuses the upgrade from a Micronaut-managed request** (for example because a servlet `Filter` or the engine touched the response, or the request is wrapped). Mitigation: the spike; the fallback is to perform the upgrade from a dedicated servlet `Filter` mapped before the Micronaut servlet that still calls the Micronaut lifecycle, which keeps the design's filter semantics.
2. **Jakarta path-template validation rejects derived paths** (Jetty `validateEndpointConfig`, Undertow `PathTemplate`). Mitigation: pass the concrete request path as the config path; path parameters are supplied explicitly anyway.
3. **Undertow `dispatchToWorkerThread` and thread-local context**: the upgrade must run on the servlet thread; async continuation paths must be excluded in code, not by convention. Covered by a test that a reactive filter in front of the upgrade still upgrades.
4. **Send-state exceptions on Tomcat** when Basic and Async remotes are mixed. Mitigation: a single async write path with the per-session queue.
5. **Native image**: missing metadata for container websocket internals. Mitigation: phase 4 on Jetty first; Tomcat and Undertow native support documented as best effort until green.
6. **Core `@Internal` binder classes** (`WebSocketState`, `WebSocketStateBinderRegistry`): a core release could change them. Mitigation: copy into the module if core maintainers do not want to widen the API.
7. **`RequestLifecycle.runWithFilters` is `protected final`**: usable from a subclass in `servlet-core`, as Netty does, but any core refactor of the lifecycle affects both servers equally.

---

## 7. Out of scope, explicitly

- **JDK `HttpServer` runtime (`http-server-jdk`)**: `com.sun.net.httpserver` has no protocol-upgrade API, no `ServletContext`, and its request/response shims are hand-written. Supporting WebSocket there would mean implementing RFC 6455 framing by hand on top of `HttpExchange`'s raw streams. Not planned. The JDK TCK suite excludes the `websocket` tag.
- **POJA (`http-poja-*`)**: a serial request/response stream over stdin/stdout (or an inherited socket) with no request concurrency and no servlet container; a long-lived full-duplex connection is structurally impossible. The POJA TCK suite excludes the tag.
- **A non-Netty (`java.net.http`) Micronaut WebSocket client**: feasible against the core SPI, but a core project, not a servlet one (section 3).
- **Jakarta `@ServerEndpoint` POJO endpoints as Micronaut beans**: applications may register them with the bootstrapped `ServerContainer` themselves; they run outside Micronaut filters and the docs will say so.
- **Partial-message delivery, WebSocket over HTTP/2 (RFC 8441), per-message-deflate tuning beyond on/off.**
