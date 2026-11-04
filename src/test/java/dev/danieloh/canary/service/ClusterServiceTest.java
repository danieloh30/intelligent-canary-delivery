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
    @Test void releaseRefreshPatchesOnlyTheNamedApplicationAndReportsPermissionFailures() throws Exception {
        for(int code:new int[]{200,403}) {
            var request=new AtomicReference<String>();var payload=new AtomicReference<String>();
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                int responseCode=exchange.getRequestMethod().equals("PATCH")?code:200;
                if(exchange.getRequestMethod().equals("PATCH")){request.set(exchange.getRequestMethod()+" "+exchange.getRequestURI().getPath());payload.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));}
                byte[] bytes=(responseCode==200?"{\"apiVersion\":\"argoproj.io/v1alpha1\",\"kind\":\"Application\",\"metadata\":{\"name\":\"canary-checkout\"}}":"{\"kind\":\"Status\",\"status\":\"Failure\",\"reason\":\"Forbidden\",\"code\":403}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(responseCode,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();
            try(var kube=new KubernetesClientBuilder().withConfig(new ConfigBuilder().withMasterUrl("http://127.0.0.1:"+server.getAddress().getPort()).build()).build()) {
                var service=new ClusterService();service.kube=kube;service.gitopsNamespace="demo-gitops";
                assertFalse(service.requestReleaseRefresh());assertNull(request.get());
                var enabled=ClusterService.class.getDeclaredField("enabled");enabled.setAccessible(true);enabled.setBoolean(service,true);
                assertEquals(code==200,service.requestReleaseRefresh());
                assertEquals("PATCH /apis/argoproj.io/v1alpha1/namespaces/demo-gitops/applications/canary-checkout",request.get());
                assertEquals("{\"metadata\":{\"annotations\":{\"argocd.argoproj.io/refresh\":\"hard\"}}}",payload.get());
            } finally {server.stop(0);}
        }
    }

}
