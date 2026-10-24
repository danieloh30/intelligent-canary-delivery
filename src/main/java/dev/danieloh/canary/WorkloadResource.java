package dev.danieloh.canary;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class WorkloadResource {
    @ConfigProperty(name="demo.role",defaultValue="control") String role;
    @ConfigProperty(name="demo.release",defaultValue="v1") String release;
    @ConfigProperty(name="demo.inventory-url",defaultValue="http://inventory:8080") String inventoryUrl;
    @Inject MeterRegistry registry;
    @Inject Tracer tracer;
    private static final Deque<Map<String,Object>> observations=new ArrayDeque<>();
    private static final AtomicLong inventoryCalls=new AtomicLong();
    private static volatile long slowdownUntil;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @GET @Path("inventory/reserve")
    public Map<String,Object> reserve(@HeaderParam("X-Canary-Release") String revision, @HeaderParam("X-Retry-Attempt") @DefaultValue("1") int attempt) throws InterruptedException {
        if(!role.equals("inventory")) throw new NotFoundException();
        long n=inventoryCalls.incrementAndGet();
        if(System.currentTimeMillis()<slowdownUntil) Thread.sleep(650);
        // Deterministic v3-only transient contention: four failures followed by success.
        boolean retry="v3".equals(revision) && attempt<5;
        registry.counter("inventory.attempts","release",revision==null?"unknown":revision).increment();
        return Map.of("reserved",!retry,"retryable",retry);
    }
    @POST @Path("internal/fault/{seconds}")
    public Map<String,Object> fault(@PathParam("seconds") int seconds) {
        if(!role.equals("inventory") || seconds<0 || seconds>120) throw new BadRequestException();
        slowdownUntil=System.currentTimeMillis()+seconds*1000L;
        return Map.of("slowdownSeconds",seconds);
    }
    @POST @Path("checkout")
    public Map<String,Object> checkout() {
        if(!role.equals("checkout")) throw new NotFoundException();
        long start=System.nanoTime(); int attempts=0; boolean success=false;
        String traceId=Span.current().getSpanContext().getTraceId();
        int budget=release.equals("v3")?8:1;
        try {
            while(attempts<budget && !success) {
                attempts++;
                Span span=tracer.spanBuilder("inventory.reserve").setSpanKind(SpanKind.CLIENT)
                    .setAttribute("release",release).setAttribute("retry.attempt",attempts).startSpan();
                try(var scope=span.makeCurrent()) {
                    var request=HttpRequest.newBuilder(URI.create(inventoryUrl+"/inventory/reserve"))
                        .timeout(Duration.ofSeconds(3)).header("X-Canary-Release",release)
                        .header("X-Retry-Attempt",String.valueOf(attempts))
                        .header("traceparent","00-"+span.getSpanContext().getTraceId()+"-"+span.getSpanContext().getSpanId()+"-01").GET().build();
                    String body=client.send(request,HttpResponse.BodyHandlers.ofString()).body();
                    success=body.contains("\"reserved\":true");
                } finally {span.end();}
            }
        } catch(Exception ex) { if(ex instanceof InterruptedException) Thread.currentThread().interrupt(); }
        double ms=(System.nanoTime()-start)/1_000_000.0;
        registry.counter("checkout.requests","release",release,"outcome",success?"success":"failure").increment();
        registry.counter("checkout.dependency.attempts","release",release).increment(attempts);
        registry.timer("checkout.duration","release",release).record(Duration.ofNanos(System.nanoTime()-start));
        Map<String,Object> event=Map.of("at",Instant.now().toString(),"release",release,"traceId",traceId,
            "attempts",attempts,"latencyMs",ms,"success",success);
        synchronized(observations) {observations.addLast(event);while(observations.size()>2000)observations.removeFirst();}
        org.jboss.logging.Logger.getLogger("checkout").infof("traceId=%s release=%s attempts=%d success=%s latencyMs=%.1f",traceId,release,attempts,success,ms);
        if(!success) throw new WebApplicationException(jakarta.ws.rs.core.Response.status(503).entity(event).build());
        return event;
    }
    @GET @Path("internal/observations")
    public List<Map<String,Object>> observations() {
        if(!role.equals("checkout")) throw new NotFoundException();
        Instant cutoff=Instant.now().minusSeconds(60);
        synchronized(observations) {return observations.stream().filter(x->Instant.parse((String)x.get("at")).isAfter(cutoff)).toList();}
    }
}
