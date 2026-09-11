package bench;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
@Controller("/")
public class HelloController {
    @Serdeable public record Greeting(int id, String message, List<String> tags) {}
    @Get(uri="/plaintext", produces=MediaType.TEXT_PLAIN) public String plaintext(){ return "Hello, World!"; }
    @Get(uri="/json") public Greeting json(@QueryValue(defaultValue="World") String name){ return new Greeting(1, "Hello, "+name+"!", List.of("a","b","c")); }
    @Post(uri="/echo") public Greeting echo(@Body Greeting g){ return g; }
}
