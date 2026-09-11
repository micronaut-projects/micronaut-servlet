package bench;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
/** A legacy-style reactive filter so every request traverses reactive logic in the filter pipeline. */
@Filter("/**")
@Requires(property = "bench.filter", notEquals = "false", defaultValue = "true")
public class ReactiveHeaderFilter implements HttpServerFilter {
    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        return Mono.fromCallable(() -> Thread.currentThread().getName())
            .flatMap(t -> Mono.from(chain.proceed(request)).map(r -> r.header("X-Filter-Thread", t)));
    }
}
