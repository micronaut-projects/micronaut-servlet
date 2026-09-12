package bench;
import io.micronaut.runtime.Micronaut;
public class Application { public static void main(String[] a){ long t=System.nanoTime(); var ctx=Micronaut.run(Application.class,a); System.err.println("STARTUP_MS="+(System.nanoTime()-t)/1_000_000+" server="+System.getProperty("server.name")); } }
