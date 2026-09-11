package bench;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import reactor.core.publisher.Mono;
import java.time.Duration;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
@Controller("/")
public class AsyncController {
    /** Blocking sleep: on Netty this is auto-dispatched to the blocking executor; on servlet it runs on the container thread. */
    @Get(uri="/sleep", produces=MediaType.TEXT_PLAIN)
    public String sleep(@QueryValue(defaultValue="100") long ms) throws InterruptedException { Thread.sleep(ms); return "slept " + ms + " on " + Thread.currentThread().isVirtual(); }
    /** Non-blocking delay through the reactive pipeline. */
    @Get(uri="/mono", produces=MediaType.TEXT_PLAIN)
    public Mono<String> mono(@QueryValue(defaultValue="100") long ms) { return Mono.delay(Duration.ofMillis(ms)).map(t -> "delayed " + ms); }
    /** Blocking sleep explicitly dispatched to the blocking executor (virtual threads when supported). */
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Get(uri="/sleep-blocking", produces=MediaType.TEXT_PLAIN)
    public String sleepBlocking(@QueryValue(defaultValue="100") long ms) throws InterruptedException { Thread.sleep(ms); return "slept " + ms + " on " + Thread.currentThread().isVirtual(); }
    @Get(uri="/thread", produces=MediaType.TEXT_PLAIN)
    public String thread() { return Thread.currentThread().toString(); }
}
