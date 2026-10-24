package dev.danieloh.canary;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.scheduler.Scheduled;
import java.net.*;

@ApplicationScoped
public class TrafficGenerator {
    @ConfigProperty(name="demo.role",defaultValue="control") String role;
    @ConfigProperty(name="demo.checkout-url",defaultValue="http://checkout:8080") String url;
    @Scheduled(every="1s",concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    void generate() {
        if(!role.equals("traffic"))return;
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for(int i=0;i<30;i++)executor.submit(()-> {
                HttpURLConnection connection=null;
                try {
                    connection=(HttpURLConnection)URI.create(url+"/checkout").toURL().openConnection();
                    connection.setConnectTimeout(2000);connection.setReadTimeout(5000);
                    // Kubernetes Service routing happens per TCP connection. Avoid a pooled
                    // connection sticking to stable pods throughout the entire canary.
                    connection.setRequestProperty("Connection","close");
                    connection.setRequestMethod("POST");connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(0);
                    connection.getOutputStream().close();
                    var stream=connection.getResponseCode()<400?connection.getInputStream():connection.getErrorStream();
                    if(stream!=null)try(stream){stream.transferTo(java.io.OutputStream.nullOutputStream());}
                } catch(Exception ignored) {
                    if(ignored instanceof InterruptedException)Thread.currentThread().interrupt();
                } finally {if(connection!=null)connection.disconnect();}
            });
        }
    }
}
