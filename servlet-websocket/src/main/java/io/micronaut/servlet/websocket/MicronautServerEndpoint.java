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
package io.micronaut.servlet.websocket;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.bind.BoundExecutable;
import io.micronaut.core.bind.DefaultExecutableBinder;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.value.ConvertibleValues;
import io.micronaut.core.execution.CompletableFutureExecutionFlow;
import io.micronaut.core.execution.ExecutionFlow;
import io.micronaut.core.io.buffer.ByteArrayBufferFactory;
import io.micronaut.core.util.KotlinUtils;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.http.context.ServerHttpRequestContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.bind.binders.ContinuationArgumentBinder;
import io.micronaut.http.server.CoroutineHelper;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.inject.MethodExecutionHandle;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.http.reactive.execution.ReactiveExecutionFlow;
import io.micronaut.websocket.CloseReason;
import io.micronaut.websocket.WebSocketPongMessage;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.bind.WebSocketState;
import io.micronaut.websocket.context.WebSocketBean;
import io.micronaut.websocket.event.WebSocketMessageProcessedEvent;
import io.micronaut.websocket.event.WebSocketSessionClosedEvent;
import io.micronaut.websocket.event.WebSocketSessionOpenEvent;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.util.context.Context;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The {@link Endpoint} that adapts a Jakarta WebSocket connection onto a Micronaut
 * {@code @ServerWebSocket} bean.
 *
 * <p>One instance exists per connection. All per-connection state arrives through the
 * {@link EndpointConfig} user properties, so the class also works on containers that
 * instantiate endpoints reflectively rather than through the configurator.</p>
 *
 * @author graemerocher
 * @since 6.2.0
 */
@Internal
public class MicronautServerEndpoint extends Endpoint {

    /**
     * The user property under which the {@link WebSocketUpgradeContext} is passed.
     */
    public static final String CONTEXT_PROPERTY = "io.micronaut.servlet.websocket.CONTEXT";

    private static final Logger LOG = LoggerFactory.getLogger(MicronautServerEndpoint.class);
    private static final String CONNECTION_RESET = "Connection reset";
    private static final String MAX_PAYLOAD_LENGTH = "maxPayloadLength";

    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean opened;

    private @Nullable WebSocketUpgradeContext context;
    private @Nullable ServletWebSocketSupport support;
    private @Nullable WebSocketBean<Object> webSocketBean;
    private @Nullable ServletWebSocketSession micronautSession;
    private @Nullable Argument<?> messageBodyArgument;
    private @Nullable Argument<?> pongBodyArgument;

    /**
     * Public no-argument constructor, required by containers that create endpoint
     * instances reflectively rather than through the configurator. The context is then
     * read from the {@link EndpointConfig} user properties instead.
     */
    public MicronautServerEndpoint() {
    }

    /**
     * Constructor used by {@link MicronautEndpointConfigurator}.
     *
     * @param context The per-upgrade context
     */
    MicronautServerEndpoint(WebSocketUpgradeContext context) {
        this.context = context;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onOpen(Session session, EndpointConfig config) {
        WebSocketUpgradeContext ctx = context != null
            ? context
            : (WebSocketUpgradeContext) config.getUserProperties().get(CONTEXT_PROPERTY);
        if (ctx == null) {
            LOG.error("No Micronaut WebSocket context available for session [{}], closing", session.getId());
            closeQuietly(session, CloseReason.INTERNAL_ERROR);
            return;
        }
        this.context = ctx;
        this.support = ctx.support();
        this.webSocketBean = ctx.webSocketBean();

        ConvertibleValues<Object> uriVariables = ConvertibleValues.of(ctx.routeMatch().getVariableValues());
        this.micronautSession = new ServletWebSocketSession(
            session,
            ctx.originatingRequest(),
            uriVariables,
            support.sessionRegistry(),
            support.encoder(),
            support.configuration().getMaxPendingSends()
        );

        applyLimits(session);

        MethodExecutionHandle<Object, ?> messageMethod = webSocketBean.messageMethod().orElse(null);
        if (messageMethod != null) {
            this.messageBodyArgument = resolveBodyArgument(messageMethod, false);
            if (messageBodyArgument == null) {
                // Validated before the session is registered and before the open event, so a
                // listener never sees a close that had no matching open.
                LOG.error("WebSocket @OnMessage method [{}] must declare exactly one message body argument", messageMethod.getExecutableMethod());
                closeQuietly(session, CloseReason.INTERNAL_ERROR);
                return;
            }
        }
        support.sessionRegistry().register(micronautSession);
        if (messageMethod != null) {
            session.addMessageHandler(String.class, (MessageHandler.Whole<String>) this::onTextMessage);
            session.addMessageHandler(ByteBuffer.class, (MessageHandler.Whole<ByteBuffer>) this::onBinaryMessage);
        }

        MethodExecutionHandle<Object, ?> pongMethod = webSocketBean.pongMethod().orElse(null);
        if (pongMethod != null) {
            this.pongBodyArgument = resolveBodyArgument(pongMethod, true);
            if (pongBodyArgument != null) {
                session.addMessageHandler(PongMessage.class, (MessageHandler.Whole<PongMessage>) this::onPongMessage);
            } else {
                LOG.error("WebSocket pong handler [{}] must declare exactly one WebSocketPongMessage argument", pongMethod.getExecutableMethod());
            }
        }

        MethodExecutionHandle<Object, ?> openMethod = webSocketBean.openMethod().orElse(null);
        if (openMethod != null) {
            invokeOpen(openMethod);
        }
        opened = true;
        support.applicationContext().publishEvent(new WebSocketSessionOpenEvent(micronautSession));
    }

    @Override
    public void onClose(Session session, jakarta.websocket.CloseReason closeReason) {
        handleClose(new CloseReason(
            closeReason.getCloseCode().getCode(),
            closeReason.getReasonPhrase()
        ));
    }

    @Override
    public void onError(Session session, Throwable throwable) {
        forwardError(throwable);
    }

    private void applyLimits(Session session) {
        ServletWebSocketConfiguration configuration = support.configuration();
        Integer maxPayloadLength = webSocketBean.messageMethod()
            .flatMap(MicronautServerEndpoint::declaredMaxPayloadLength)
            .orElse(null);
        session.setMaxTextMessageBufferSize(
            maxPayloadLength != null ? maxPayloadLength : configuration.getMaxTextMessageSize());
        session.setMaxBinaryMessageBufferSize(
            maxPayloadLength != null ? maxPayloadLength : configuration.getMaxBinaryMessageSize());
        session.setMaxIdleTimeout(support.idleTimeout().toMillis());
    }

    /**
     * Reads {@code maxPayloadLength} only when the endpoint actually declared it.
     *
     * <p>The annotation carries a default, so a value read straight from the metadata cannot
     * be told apart from an unset one, and the configured size would then never apply.</p>
     *
     * @param messageMethod The message handler
     * @return The declared payload length, or empty when the endpoint did not declare one
     */
    private static Optional<Integer> declaredMaxPayloadLength(MethodExecutionHandle<Object, ?> messageMethod) {
        return messageMethod.getAnnotationMetadata()
            .findAnnotation(OnMessage.class)
            .filter(annotation -> annotation.contains(MAX_PAYLOAD_LENGTH))
            .flatMap(annotation -> annotation.intValue(MAX_PAYLOAD_LENGTH).stream().boxed().findFirst());
    }

    private void onTextMessage(String message) {
        handleMessage(message, null);
    }

    private void onBinaryMessage(ByteBuffer message) {
        byte[] bytes = new byte[message.remaining()];
        message.get(bytes);
        handleMessage(null, bytes);
    }

    private void onPongMessage(PongMessage message) {
        MethodExecutionHandle<Object, ?> pongMethod = webSocketBean.pongMethod().orElse(null);
        if (pongMethod == null || pongBodyArgument == null) {
            return;
        }
        ByteBuffer applicationData = message.getApplicationData();
        byte[] bytes = new byte[applicationData.remaining()];
        applicationData.get(bytes);
        WebSocketPongMessage pong = new WebSocketPongMessage(ByteArrayBufferFactory.INSTANCE.wrap(bytes));
        invoke(pongMethod, Map.of(pongBodyArgument, pong), null);
    }

    /**
     * Invokes {@code @OnOpen}, closing the connection if it fails.
     *
     * <p>A handler failure is offered to {@code @OnError} first, but unlike a message
     * failure the session is always closed afterwards: an endpoint whose initialization
     * failed must not go on receiving messages. This matches the Netty server, which closes
     * unconditionally when the open method or its binding fails.</p>
     *
     * @param openMethod The open handler
     */
    private void invokeOpen(MethodExecutionHandle<Object, ?> openMethod) {
        invokeHandler(openMethod, Map.of()).onComplete((ignored, error) -> {
            if (error != null) {
                failOpen(error);
            }
        });
    }

    private void failOpen(Throwable cause) {
        if (LOG.isErrorEnabled()) {
            LOG.error("Error opening WebSocket session: {}", cause.getMessage(), cause);
        }
        try {
            forwardError(cause);
        } finally {
            closeSession(CloseReason.INTERNAL_ERROR);
        }
    }

    private void handleMessage(@Nullable String text, byte @Nullable [] bytes) {
        MethodExecutionHandle<Object, ?> messageMethod = webSocketBean.messageMethod().orElse(null);
        if (messageMethod == null || messageBodyArgument == null) {
            closeSession(CloseReason.UNSUPPORTED_DATA);
            return;
        }
        Object decoded;
        try {
            decoded = decode(messageMethod, messageBodyArgument, text, bytes);
        } catch (Exception e) {
            forwardError(e);
            return;
        }
        if (decoded == null) {
            closeSession(new CloseReason(
                CloseReason.UNSUPPORTED_DATA.getCode(),
                "Received data cannot be data to target type: " + messageBodyArgument
            ));
            return;
        }
        invoke(messageMethod, Map.of(messageBodyArgument, decoded), decoded);
    }

    @SuppressWarnings("unchecked")
    private @Nullable Object decode(MethodExecutionHandle<Object, ?> messageMethod,
                                    Argument<?> bodyArgument,
                                    @Nullable String text,
                                    byte @Nullable [] bytes) {
        Object raw = text != null ? text : bytes;
        Object converted = support.conversionService()
            .convert(raw, ConversionContext.of((Argument<Object>) bodyArgument))
            .orElse(null);
        if (converted != null) {
            return converted;
        }
        byte[] data = bytes != null ? bytes : text.getBytes(StandardCharsets.UTF_8);
        MediaType mediaType = messageMethod.stringValue(Consumes.class)
            .map(MediaType::of)
            .orElse(MediaType.APPLICATION_JSON_TYPE);
        MessageBodyReader<Object> reader = support.messageBodyHandlerRegistry()
            .findReader((Argument<Object>) bodyArgument, List.of(mediaType))
            .orElse(null);
        if (reader == null) {
            return null;
        }
        return reader.read(
            (Argument<Object>) bodyArgument,
            mediaType,
            new SimpleHttpHeaders(support.conversionService()),
            new ByteArrayInputStream(data)
        );
    }

    private @Nullable Argument<?> resolveBodyArgument(MethodExecutionHandle<Object, ?> handle, boolean pong) {
        WebSocketState state = new WebSocketState(micronautSession, context.originatingRequest());
        BoundExecutable<Object, ?> probe = new DefaultExecutableBinder<WebSocketState>()
            .tryBind(handle.getExecutableMethod(), support.binderRegistry(), state);
        List<Argument<?>> unbound = probe.getUnboundArguments();
        if (unbound.size() != 1) {
            return null;
        }
        Argument<?> argument = unbound.get(0);
        if (pong && !argument.getType().isAssignableFrom(WebSocketPongMessage.class)) {
            return null;
        }
        return argument;
    }

    private void invoke(MethodExecutionHandle<Object, ?> handle,
                        Map<Argument<?>, Object> preBound,
                        @Nullable Object processedMessage) {
        invokeHandler(handle, preBound).onComplete((ignored, error) -> {
            if (error != null) {
                forwardError(error);
            } else if (processedMessage != null) {
                support.applicationContext()
                    .publishEvent(new WebSocketMessageProcessedEvent<>(micronautSession, processedMessage));
            }
        });
    }

    /**
     * Binds and invokes a handler on the executor selected for it, so that
     * {@code @ExecuteOn} is honoured, with the originating request propagated so that
     * {@code ServerRequestContext.currentRequest()} resolves inside the handler.
     *
     * <p>Binding happens inside the executor task rather than on the container thread,
     * because a Kotlin {@code suspend} handler has its continuation registered on the
     * request during binding and read back after the call. Doing both in one task, against a
     * request that belongs to this invocation, keeps concurrent messages on one session from
     * overwriting each other's continuation.</p>
     *
     * @param handle   The handler
     * @param preBound Arguments already resolved, such as the message body
     * @return A flow completing when the handler, and any publisher it returned, completes
     */
    private ExecutionFlow<Object> invokeHandler(MethodExecutionHandle<Object, ?> handle,
                                                Map<Argument<?>, Object> preBound) {
        Executor executor = support.executorSelector()
            .selectExecutor(handle.getExecutableMethod(), support.threadSelectionConfiguration());
        boolean suspend = handle.getExecutableMethod().isSuspend();
        PropagatedContext propagatedContext = PropagatedContext.getOrEmpty()
            .plus(new ServerHttpRequestContext(context.originatingRequest()));
        return ExecutionFlow.async(executor, () -> propagatedContext.propagate(() -> {
            try {
                HttpRequest<?> request = suspend
                    ? WebSocketHandshakeRequest.snapshot(context.originatingRequest())
                    : context.originatingRequest();
                BoundExecutable<Object, ?> bound = new DefaultExecutableBinder<WebSocketState>(preBound)
                    .bind(handle.getExecutableMethod(), support.binderRegistry(),
                        new WebSocketState(micronautSession, request));
                if (suspend) {
                    return invokeSuspend(bound, request, propagatedContext);
                }
                return toFlow(bound.invoke(webSocketBean.getTarget()), propagatedContext);
            } catch (Throwable e) {
                return ExecutionFlow.error(e);
            }
        }));
    }

    /**
     * Invokes a Kotlin {@code suspend} handler.
     *
     * <p>The coroutine context has to be set up before the call, and a handler that really
     * suspends returns the {@code COROUTINE_SUSPENDED} marker rather than its result, so the
     * completion has to be picked up from the continuation instead. Without this a suspending
     * handler would be treated as already finished and neither its result nor its failure
     * would be seen.</p>
     *
     * @param bound             The bound handler
     * @param request           The request this invocation owns, which holds its continuation
     * @param propagatedContext The context to run the handler under
     * @return A flow completing when the coroutine completes
     */
    private ExecutionFlow<Object> invokeSuspend(BoundExecutable<Object, ?> bound,
                                                HttpRequest<?> request,
                                                PropagatedContext propagatedContext) {
        CoroutineHelper coroutineHelper = support.coroutineHelper();
        if (coroutineHelper == null) {
            return toFlow(bound.invoke(webSocketBean.getTarget()), propagatedContext);
        }
        coroutineHelper.setupCoroutineContext(request, Context.empty(), propagatedContext);
        Object result = bound.invoke(webSocketBean.getTarget());
        if (!KotlinUtils.isKotlinCoroutineSuspended(result)) {
            return ExecutionFlow.just(null);
        }
        Supplier<CompletableFuture<?>> completion =
            ContinuationArgumentBinder.extractContinuationCompletableFutureSupplier(request);
        if (completion == null) {
            return ExecutionFlow.just(null);
        }
        return CompletableFutureExecutionFlow.just(completion.get().thenApply(value -> (Object) value));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ExecutionFlow<Object> toFlow(@Nullable Object result, PropagatedContext propagatedContext) {
        if (result == null) {
            return ExecutionFlow.just(null);
        }
        if (Publishers.isConvertibleToPublisher(result)) {
            // Subscribed eagerly: a lazy subscription would happen after the propagation
            // scope has exited, leaving the handler's reactive code without the request.
            return ReactiveExecutionFlow.fromPublisherEager(
                Publishers.convertToPublisher(support.conversionService(), result),
                propagatedContext
            );
        }
        if (result instanceof CompletionStage<?> stage) {
            return CompletableFutureExecutionFlow.just((CompletionStage<Object>) stage);
        }
        return ExecutionFlow.just(result);
    }

    private void handleClose(CloseReason reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        MethodExecutionHandle<Object, ?> closeMethod = webSocketBean != null
            ? webSocketBean.closeMethod().orElse(null)
            : null;
        if (closeMethod == null) {
            finishClose();
            return;
        }
        invokeHandler(closeMethod, preBind(closeMethod.getExecutableMethod(), reason)).onComplete((ignored, error) -> {
            if (error != null && LOG.isErrorEnabled()) {
                LOG.error("Error invoking @OnClose handler: {}", error.getMessage(), error);
            }
            finishClose();
        });
    }

    private void finishClose() {
        if (micronautSession == null) {
            return;
        }
        support.sessionRegistry().deregister(micronautSession);
        if (opened) {
            // A session that never reached the open event must not produce a close event,
            // or listeners see a close with no matching open.
            support.applicationContext().publishEvent(new WebSocketSessionClosedEvent(micronautSession));
        }
    }

    private void forwardError(Throwable cause) {
        MethodExecutionHandle<Object, ?> errorMethod = webSocketBean != null
            ? webSocketBean.errorMethod().orElse(null)
            : null;
        if (errorMethod == null) {
            handleUnexpected(cause);
            return;
        }
        invokeHandler(errorMethod, preBind(errorMethod.getExecutableMethod(), cause)).onComplete((ignored, error) -> {
            if (error != null) {
                if (LOG.isErrorEnabled()) {
                    LOG.error("Error invoking @OnError handler: {}", error.getMessage(), error);
                }
                // The original failure is what the connection has to be closed for.
                handleUnexpected(cause);
            }
        });
    }

    private void handleUnexpected(Throwable cause) {
        if (cause instanceof IOException && cause.getMessage() != null && cause.getMessage().contains(CONNECTION_RESET)) {
            if (LOG.isTraceEnabled()) {
                LOG.trace("WebSocket connection reset: {}", cause.getMessage(), cause);
            }
            return;
        }
        if (LOG.isErrorEnabled()) {
            LOG.error("Unexpected error in WebSocket handler: {}", cause.getMessage(), cause);
        }
        closeSession(CloseReason.INTERNAL_ERROR);
    }

    private void closeSession(CloseReason reason) {
        if (micronautSession != null) {
            micronautSession.close(reason);
        }
    }

    private static void closeQuietly(Session session, CloseReason reason) {
        try {
            session.close(new jakarta.websocket.CloseReason(
                jakarta.websocket.CloseReason.CloseCodes.getCloseCode(reason.getCode()),
                reason.getReason()
            ));
        } catch (Exception e) {
            // ignore, the connection is going away anyway
        }
    }

    /**
     * Matches a value against the first handler argument that can hold it, the way the
     * Netty implementation pre-binds the close reason and the error cause.
     *
     * @param method The handler method
     * @param value  The value to pre-bind
     * @return The pre-bound arguments, empty when no argument accepts the value
     */
    static Map<Argument<?>, Object> preBind(ExecutableMethod<?, ?> method, Object value) {
        for (Argument<?> argument : method.getArguments()) {
            if (argument.getType().isInstance(value)) {
                return Map.of(argument, value);
            }
        }
        return Map.of();
    }
}
