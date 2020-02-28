package io.micronaut.servlet.engine.bind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.bind.DefaultRequestBinderRegistry;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.http.codec.MediaTypeCodec;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.jackson.codec.JsonMediaTypeCodec;
import io.micronaut.jackson.parser.JacksonProcessor;
import io.micronaut.servlet.http.ServletBodyBinder;
import io.micronaut.servlet.http.StreamedServletMessage;
import io.reactivex.Flowable;
import org.reactivestreams.Subscriber;

import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Replaces the {@link DefaultRequestBinderRegistry} with one capable of binding from servlet requests.
 *
 * @author graemerocher
 * @since 1.0.0
 */
@Singleton
@Replaces(DefaultRequestBinderRegistry.class)
@Internal
class DefaultServletBinderRegistry extends io.micronaut.servlet.http.ServletBinderRegistry {

    public static final Argument<byte[]> BYTE_ARRAY = Argument.of(byte[].class);

    /**
     * Default constructor.
     *
     * @param mediaTypeCodecRegistry The media type codec registry
     * @param conversionService      The conversion service
     * @param binders                Any registered binders
     */
    public DefaultServletBinderRegistry(
            MediaTypeCodecRegistry mediaTypeCodecRegistry,
            ConversionService conversionService,
            List<RequestArgumentBinder> binders) {
        super(mediaTypeCodecRegistry, conversionService, binders);
        byType.put(HttpServletRequest.class, new ServletRequestBinder());
        byType.put(HttpServletResponse.class, new ServletResponseBinder());
        byType.put(CompletedPart.class, new CompletedPartRequestArgumentBinder());
        byAnnotation.put(Part.class, new ServletPartBinder(mediaTypeCodecRegistry));
    }

    @SuppressWarnings("unchecked")
    @Override
    protected ServletBodyBinder newServletBodyBinder(MediaTypeCodecRegistry mediaTypeCodecRegistry, ConversionService conversionService) {
        return new DefaultServletBodyBinder(conversionService, mediaTypeCodecRegistry);
    }

    /**
     * Overridden body binder.
     */
    private static class DefaultServletBodyBinder extends ServletBodyBinder {
        private final MediaTypeCodecRegistry mediaTypeCodecRegistry;

        public DefaultServletBodyBinder(ConversionService conversionService, MediaTypeCodecRegistry mediaTypeCodecRegistry) {
            super(conversionService, mediaTypeCodecRegistry);
            this.mediaTypeCodecRegistry = mediaTypeCodecRegistry;
        }

        @Override
        public BindingResult bind(ArgumentConversionContext context, HttpRequest source) {
            Argument<?> argument = context.getArgument();
            Class<?> type = argument.getType();
            if (CompletionStage.class.isAssignableFrom(type)) {
                StreamedServletMessage<?, byte[]> servletHttpRequest = (StreamedServletMessage<?, byte[]>) source;
                CompletableFuture<Object> future = new CompletableFuture<>();
                Argument<?> typeArgument = argument.getFirstTypeVariable().orElse(BYTE_ARRAY);
                Class<?> javaArgument = typeArgument.getType();
                Charset characterEncoding = servletHttpRequest.getCharacterEncoding();
                if (CharSequence.class.isAssignableFrom(javaArgument)) {
                    Flowable.fromPublisher(servletHttpRequest).collect(StringBuilder::new, (stringBuilder, bytes) ->
                            stringBuilder.append(new String(bytes, characterEncoding))
                    ).subscribe((stringBuilder, throwable) -> {
                            if (throwable != null) {
                                future.completeExceptionally(throwable);
                            } else {
                                future.complete(stringBuilder.toString());
                            }
                    });
                } else if (BYTE_ARRAY.getType().isAssignableFrom(type)) {
                    Flowable.fromPublisher(servletHttpRequest).collect(ByteArrayOutputStream::new, OutputStream::write)
                            .subscribe((stream, throwable) -> {
                                if (throwable != null) {
                                    future.completeExceptionally(throwable);
                                } else {
                                    future.complete(stream.toByteArray());
                                }
                            });
                } else {
                    MediaType mediaType = servletHttpRequest.getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);
                    MediaTypeCodec codec = mediaTypeCodecRegistry.findCodec(mediaType, javaArgument).orElse(null);
                    if (codec instanceof JsonMediaTypeCodec) {
                        JsonMediaTypeCodec jsonCodec = (JsonMediaTypeCodec) codec;
                        ObjectMapper objectMapper = jsonCodec.getObjectMapper();
                        JacksonProcessor jacksonProcessor = new JacksonProcessor(
                                objectMapper.getFactory(),
                                false,
                                objectMapper.getDeserializationConfig()
                        );
                        Flowable.fromPublisher(servletHttpRequest)
                                .subscribe(jacksonProcessor);
                        Flowable.fromPublisher(jacksonProcessor)
                                .firstElement()
                                .subscribe((jsonNode) -> {
                                    try {
                                        future.complete(jsonCodec.decode(typeArgument, jsonNode));
                                    } catch (Exception e) {
                                        future.completeExceptionally(e);
                                    }
                                }, (future::completeExceptionally));
                    } else {
                        return super.bind(context, source);
                    }
                }
                return () -> Optional.of(future);
            } else if (CompletedPart.class.isAssignableFrom(type)) {
                return new CompletedPartRequestArgumentBinder().bind(context, source);
            } else {
                if (Publishers.isConvertibleToPublisher(type)) {
                    Argument<?> typeArgument = argument.getFirstTypeVariable().orElse(BYTE_ARRAY);
                    Class<?> javaArgument = typeArgument.getType();

                    StreamedServletMessage<?, byte[]> servletHttpRequest = (StreamedServletMessage<?, byte[]>) source;
                    Charset characterEncoding = servletHttpRequest.getCharacterEncoding();
                    if (CharSequence.class.isAssignableFrom(javaArgument)) {
                        Flowable<String> stringFlowable = Flowable.fromPublisher(servletHttpRequest)
                                .map(bytes -> new String(bytes, characterEncoding));
                        if (type.isInstance(stringFlowable)) {
                            return () -> Optional.of(stringFlowable);
                        } else {
                            Object converted = Publishers.convertPublisher(stringFlowable, type);
                            return () -> Optional.of(converted);
                        }
                    } else if (byte[].class.isAssignableFrom(javaArgument)) {
                        return () -> Optional.of(Flowable.fromPublisher(servletHttpRequest));
                    } else {
                        MediaType mediaType = servletHttpRequest.getContentType().orElse(MediaType.APPLICATION_JSON_TYPE);
                        MediaTypeCodec codec = mediaTypeCodecRegistry.findCodec(mediaType, javaArgument).orElse(null);
                        if (codec instanceof JsonMediaTypeCodec) {
                            JsonMediaTypeCodec jsonCodec = (JsonMediaTypeCodec) codec;
                            ObjectMapper objectMapper = jsonCodec.getObjectMapper();
                            JacksonProcessor jacksonProcessor = new JacksonProcessor(
                                    objectMapper.getFactory(),
                                    false,
                                    objectMapper.getDeserializationConfig()
                            ) {
                                @Override
                                public void subscribe(Subscriber<? super JsonNode> downstreamSubscriber) {
                                    servletHttpRequest.subscribe(this);
                                    super.subscribe(downstreamSubscriber);
                                }
                            };
                            Flowable<?> jsonDecoder = Flowable.fromPublisher(jacksonProcessor)
                                    .map((jsonNode -> jsonCodec.decode(typeArgument, jsonNode)));

                            Object converted = Publishers.convertPublisher(jsonDecoder, type);
                            return () -> Optional.of(converted);
                        } else {
                            return super.bind(context, source);
                        }
                    }
                } else {
                    return super.bind(context, source);
                }
            }
        }
    }
}
