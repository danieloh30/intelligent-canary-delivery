package dev.danieloh.canary.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Protocol stub: exercises real @Agent/tool wiring without sending telemetry to an external model. */
public class MockOpenAi implements QuarkusTestResourceLifecycleManager {
    private HttpServer server;
    public static final AtomicInteger requests=new AtomicInteger();
    public static volatile String numericalScenario="";
    public static final AtomicInteger malformedResponses=new AtomicInteger();
    public static volatile boolean forceHealthy=false;
    public static volatile boolean mixedUnits=false;
    public Map<String,String> start() {
        try {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            ObjectMapper json=new ObjectMapper();requests.set(0);
            server.createContext("/v1/chat/completions",exchange->{
                try {
                    requests.incrementAndGet();
                    JsonNode input=json.readTree(exchange.getRequestBody());
                    var matcher=Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matcher(input.toString());
                    String id=matcher.find()?matcher.group():"unknown";
                    boolean usedTools=false;for(var message:input.path("messages")) {
                        if(message.path("role").asText().equals("user"))usedTools=false;
                        if(message.path("role").asText().equals("tool"))usedTools=true;
                    }
                    boolean decision=input.toString().contains("Synthesize specialist findings");
                    Map<String,Object> message=new LinkedHashMap<>();message.put("role","assistant");
                    String finish="stop";
                    if(!usedTools && !input.path("tools").isEmpty()) {
                        List<Map<String,Object>> calls=new ArrayList<>();int n=0;
                        for(var tool:input.path("tools")) calls.add(Map.of("id","call_"+(n++),"type","function","function",Map.of("name",tool.path("function").path("name").asText(),"arguments",json.writeValueAsString(Map.of("evaluation_id",id)))));
                        message.put("tool_calls",calls);finish="tool_calls";
                    } else if(mixedUnits) {
                        var answer=new LinkedHashMap<String,Object>();
                        answer.put("summary","Both revisions exceed the minimum request requirement of 30 requests and maintain latencies well below the 500 ms limit. All requests were successful with latencies well below the 500 ms threshold.");
                        answer.put("evidenceIds",List.of(id));answer.put("limitations",List.of());if(decision)answer.put("verdict","GO");
                        message.put("content",json.writeValueAsString(answer));
                    } else if(!decision && input.toString().contains("Evaluate the metrics for investigation") && malformedResponses.getAndDecrement()>0) {
                        message.put("content","not valid structured output; raw-model-content-that-must-not-be-displayed");
                    } else if(!numericalScenario.isBlank() && (numericalScenario.equals("persistent") || !input.toString().contains("contradiction:"))) {
                        String claim=numericalScenario.equals("sample")?"Insufficient requests in canary revision (58 requests, minimum required is 30)":"Canary p95 latency exceeds the 500 ms threshold";
                        var answer=new LinkedHashMap<String,Object>();answer.put("summary",claim);answer.put("evidenceIds",List.of(id));answer.put("limitations",List.of(claim));
                        if(decision)answer.put("verdict","HOLD");
                        message.put("content",json.writeValueAsString(answer));
                    } else if(decision) {
                        message.put("content",json.writeValueAsString(Map.of("verdict",numericalScenario.isBlank()&&!forceHealthy?"NO_GO":"GO","summary",numericalScenario.isBlank()?"Canary-only dependency retry amplification":"Frozen comparisons meet safety limits","evidenceIds",List.of(id),"limitations",List.of())));
                    } else {
                        message.put("content",json.writeValueAsString(Map.of("summary",input.toString().contains("Act as Dependency Investigator")?"Dependency evidence supports canary-specific retries":input.toString().contains("Act as Release Analyst")?"Release runbook confirms retry budget regression":"Metrics evidence shows HTTP success","evidenceIds",List.of(id),"limitations",List.of())));
                    }
                    byte[] body=json.writeValueAsBytes(Map.of("id","chatcmpl-stub","object","chat.completion","created",1,"model","gpt-4o-mini","choices",List.of(Map.of("index",0,"message",message,"finish_reason",finish)),"usage",Map.of("prompt_tokens",10,"completion_tokens",10,"total_tokens",20)));
                    exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                } catch(Exception ex){byte[] body=ex.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(500,body.length);exchange.getResponseBody().write(body);}
                finally {exchange.close();}
            });
            server.start();
            return Map.of("quarkus.langchain4j.openai.base-url","http://127.0.0.1:"+server.getAddress().getPort()+"/v1/","quarkus.langchain4j.openai.api-key","protocol-test-key");
        }catch(Exception ex){throw new IllegalStateException(ex);}
    }
    public void stop(){if(server!=null)server.stop(0);}
}
