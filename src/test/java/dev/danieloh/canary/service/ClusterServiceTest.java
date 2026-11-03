package dev.danieloh.canary.service;

import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ClusterServiceTest {
    @Test void quarkusLogsSelectJavaContainerInPodWithOauthSidecar() throws Exception {
        var query=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String body;
            if(exchange.getRequestURI().getPath().endsWith("/log")) {
                query.set(exchange.getRequestURI().getQuery());body="Java agent tool read\n";
            } else body="{\"apiVersion\":\"v1\",\"kind\":\"PodList\",\"items\":[{\"metadata\":{\"name\":\"canary-control-test\",\"namespace\":\"canary-demo\"},\"spec\":{\"containers\":[{\"name\":\"canary-control\"},{\"name\":\"oauth-proxy\"}]}}]}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try(var kube=new KubernetesClientBuilder().withConfig(new ConfigBuilder()
                .withMasterUrl("http://127.0.0.1:"+server.getAddress().getPort()).withNamespace("canary-demo").build()).build()) {
            var service=new ClusterService();service.kube=kube;service.namespace="canary-demo";
            var enabled=ClusterService.class.getDeclaredField("enabled");enabled.setAccessible(true);enabled.setBoolean(service,true);
            var lines=service.logs("quarkus");
            assertTrue(lines.contains("Java agent tool read"),lines.toString());
            assertTrue(query.get().contains("container=canary-control"),query.get());
            assertTrue(query.get().contains("tailLines=50"),query.get());
        } finally {server.stop(0);}
    }
}
