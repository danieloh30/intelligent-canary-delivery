package dev.danieloh.canary;

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
                    } else if(decision) {
                        message.put("content",json.writeValueAsString(Map.of("verdict","NO_GO","summary","Canary-only dependency retry amplification","evidenceIds",List.of(id),"limitations",List.of())));
                    } else {
                        message.put("content",json.writeValueAsString(Map.of("summary","Evidence supports canary-specific retry amplification","evidenceIds",List.of(id),"limitations",List.of())));
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
