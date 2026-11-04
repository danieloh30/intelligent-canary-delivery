package dev.danieloh.canary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class GitReleaseServiceTest {
    @Test void commitsOnlyRolloutWithOptimisticConcurrencyAndFreshReleaseIdentity() throws Exception {
        exercise(200);
    }
    @Test void concurrentGitChangeIsReportedWithoutLeakingUpstreamResponse() throws Exception {
        exercise(409);
    }
    @Test void deniedGitHubAccessDoesNotLeakResponseOrCredential() throws Exception {
        exercise(403);
    }
    @Test void refreshFailureKeepsTheSuccessfulCommitAndReportsFallback() throws Exception {exercise(200,false);}
    void exercise(int status) throws Exception {exercise(status,true);}
    void exercise(int status,boolean refreshAllowed) throws Exception {
        var json=new ObjectMapper();
        String original=Files.readString(Path.of("deploy/gitops/rollout.yaml"));
        var put=new AtomicReference<JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();
            String body;int code=200;
            if(path.equals("/repos/demo/repository")) body="{\"name\":\"repository\",\"full_name\":\"demo/repository\",\"url\":\""+endpoint+path+"\"}";
            else if(path.equals("/repos/demo/repository/contents/"+GitReleaseService.PATH)) {
                if(exchange.getRequestMethod().equals("PUT")) {
                    put.set(json.readTree(exchange.getRequestBody()));code=status;
                    body=status==200?"{\"commit\":{\"sha\":\"new-commit\"},\"content\":{\"sha\":\"new-file\"}}":"{\"message\":\"upstream-secret\"}";
                } else body=json.writeValueAsString(Map.of("type","file","path",GitReleaseService.PATH,"sha","old-file","encoding","base64","content",Base64.getEncoder().encodeToString(original.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            } else {code=404;body="{}";}
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            var refreshes=new java.util.concurrent.atomic.AtomicInteger();
            var service=new GitReleaseService();service.cluster=new ClusterService(){
                @Override public boolean requestReleaseRefresh(){assertNotNull(put.get());assertNotNull(service.store.releaseIntent());refreshes.incrementAndGet();return refreshAllowed;}
            };service.json=json;service.store=new DemoStore();service.apiUrl=endpoint;
            service.repository="demo/repository";service.branch="main";service.token=Optional.of("test-credential");
            service.role="control";service.mode="live";service.releaseEnabled=true;
            assertThrows(jakarta.ws.rs.BadRequestException.class,()->service.release("v4"));
            assertNull(put.get());
            service.mode="fixture";assertFalse(service.enabled());assertThrows(ClientErrorException.class,()->service.release("v2"));assertNull(put.get());service.mode="live";
            service.token=Optional.empty();assertFalse(service.enabled());service.token=Optional.of("test-credential");
            service.role="inventory";assertFalse(service.enabled());service.role="control";
            if(status==200) {
                var previous=new dev.danieloh.canary.model.Evaluation(RehearsalEvidence.forScene("healthy"),"development","fixture");
                previous.status="COMPLETE";service.store.add(previous);
                var result=service.release("v2");assertEquals("new-commit",result.get("sha"));
                assertEquals(Boolean.toString(refreshAllowed),result.get("refreshRequested"));assertEquals(1,refreshes.get());
                assertNull(service.store.latest());assertSame(previous,service.store.require(previous.id));
                assertEquals("v2",service.store.releaseIntent().version());
                assertEquals("https://github.com/demo/repository/commit/new-commit",result.get("url"));
                assertEquals("old-file",put.get().path("sha").asText());assertEquals("main",put.get().path("branch").asText());
                var manifest=json.readTree(Base64.getDecoder().decode(put.get().path("content").asText()));
                var expected=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(original);
                var time=manifest.at("/spec/template/metadata/annotations/demo.canary~1released-at").asText();
                assertTrue(java.time.Instant.parse(time).isAfter(java.time.Instant.now().minusSeconds(60)));
                // Independently assert the version and both analysis stages, while preserving the rest of the manifest.
                for(var env:expected.at("/spec/template/spec/containers/0/env")) if(env.path("name").asText().equals("DEMO_RELEASE")) ((com.fasterxml.jackson.databind.node.ObjectNode)env).put("value","v2");
                ((com.fasterxml.jackson.databind.node.ObjectNode)expected.at("/spec/template/metadata/annotations")).put("demo.canary/released-at",time);
                int stages=0;
                for(var step:expected.at("/spec/strategy/canary/steps")) for(var arg:step.path("analysis").path("args")) if(arg.path("name").asText().equals("release")){((com.fasterxml.jackson.databind.node.ObjectNode)arg).put("value","v2");stages++;}
                assertEquals(2,stages);assertEquals(expected,manifest);
            } else {
                var error=status==409?assertThrows(ClientErrorException.class,()->service.release("v3")):assertThrows(ServiceUnavailableException.class,()->service.release("v3"));
                assertEquals(0,refreshes.get());assertFalse(error.getMessage().contains("upstream-secret"));assertFalse(error.getMessage().contains("test-credential"));
            }
        } finally {server.stop(0);}
    }
}
