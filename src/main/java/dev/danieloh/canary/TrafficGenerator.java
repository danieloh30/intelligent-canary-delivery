package dev.danieloh.canary;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.scheduler.Scheduled;
import java.net.*;
import java.net.http.*;
import java.time.Duration;

@ApplicationScoped
public class TrafficGenerator {
    @ConfigProperty(name="demo.role",defaultValue="control") String role;
    @ConfigProperty(name="demo.checkout-url",defaultValue="http://checkout:8080") String url;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    @Scheduled(every="1s",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    void generate() {
        if(!role.equals("traffic"))return;
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for(int i=0;i<30;i++)executor.submit(()-> {
                try {client.send(HttpRequest.newBuilder(URI.create(url+"/checkout")).timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());}
                catch(Exception ignored) {if(ignored instanceof InterruptedException)Thread.currentThread().interrupt();}
            });
        }
    }
}
