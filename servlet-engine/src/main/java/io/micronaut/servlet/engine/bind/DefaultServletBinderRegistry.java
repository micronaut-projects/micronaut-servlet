package io.micronaut.servlet.engine.bind;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.bind.DefaultRequestBinderRegistry;
import io.micronaut.http.bind.binders.RequestArgumentBinder;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.http.multipart.CompletedPart;
import io.micronaut.servlet.http.ServletBodyBinder;

import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;

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
        return new ServletBodyBinder(conversionService, mediaTypeCodecRegistry) {
            @Override
            public BindingResult bind(ArgumentConversionContext context, HttpRequest source) {
                if (CompletedPart.class.isAssignableFrom(context.getArgument().getType())) {
                    return new CompletedPartRequestArgumentBinder().bind(context, source);
                } else {
                    return super.bind(context, source);
                }
            }
        };
    }
}
