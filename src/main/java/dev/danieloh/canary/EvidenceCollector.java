package dev.danieloh.canary;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.*;
import static dev.danieloh.canary.Domain.*;

@ApplicationScoped
public class EvidenceCollector {
    @Inject ClusterService cluster;
    public Evidence collect(String expectedRevision) {
        List<String> missing=new ArrayList<>(), logs=new ArrayList<>();
        List<Map<String,Object>> stable=new ArrayList<>(),canary=new ArrayList<>();
        String stableHash="",canaryHash=""; int stepIndex=-1;
        try {
            var rollout=cluster.rollout();
            var status=(Map<?,?>)rollout.getAdditionalProperties().get("status");
            stepIndex=((Number)status.getOrDefault("currentStepIndex",null)).intValue();
            stableHash=Objects.toString(status.get("stableRS"),"");
            canaryHash=Objects.toString(status.get("currentPodHash"),"");
            if(stableHash.isBlank()||stableHash.equals(canaryHash))missing.add("Distinct stable and canary revisions are not available");
            for(var pod:cluster.client().pods().inNamespace(cluster.namespace()).withLabel("app","checkout").list().getItems()) {
                if(!"Running".equals(pod.getStatus().getPhase()))continue;
                String hash=pod.getMetadata().getLabels().get("rollouts-pod-template-hash");
                if(!stableHash.equals(hash)&&!canaryHash.equals(hash))continue;
                try {
                    var data=cluster.mapper().readValue(cluster.request("http://"+pod.getStatus().getPodIP()+":8080/internal/observations","GET"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});
                    for(var item:data)item.put("podHash",hash);
                    if(stableHash.equals(hash))stable.addAll(data);else canary.addAll(data);
                }catch(Exception ex){missing.add("Pod evidence unavailable: "+pod.getMetadata().getName());}
            }
        }catch(Exception ex){missing.add("Rollout evidence unavailable");}
        Instant cutoff=Instant.now().minusSeconds(60);
        stable.removeIf(x->Instant.parse(x.get("at").toString()).isBefore(cutoff));
        canary.removeIf(x->Instant.parse(x.get("at").toString()).isBefore(cutoff));
        if(canary.stream().anyMatch(x->!expectedRevision.equals(x.get("release"))))missing.add("Canary release does not match requested revision");
        var sampled=new ArrayList<Map<String,Object>>();
        sampled.addAll(stable.stream().skip(Math.max(0,stable.size()-5)).toList());
        sampled.addAll(canary.stream().skip(Math.max(0,canary.size()-10)).toList());
        for(var x:sampled)logs.add("traceId="+x.get("traceId")+" release="+x.get("release")+" attempts="+x.get("attempts")+" success="+x.get("success"));
        if(sampled.isEmpty())missing.add("No trace-linked request observations");
        String id=UUID.randomUUID().toString();
        return new Evidence(id,Instant.now(),"LIVE_KUBERNETES_REQUESTS",expectedRevision,stableHash,stepIndex,
            summarize(stable),summarize(canary),sampled,logs,missing,
            expectedRevision.equals("v3")?"v3: inventory retry budget increased to eight attempts":"v2: checkout response optimization");
    }
    public static Signal summarize(List<Map<String,Object>> observations) {
        if(observations.isEmpty())return new Signal(0,0,0,0);
        var sorted=observations.stream().mapToDouble(x->((Number)x.get("latencyMs")).doubleValue()).sorted().toArray();
        double errors=observations.stream().filter(x->!Boolean.TRUE.equals(x.get("success"))).count()/(double)observations.size();
        double attempts=observations.stream().mapToDouble(x->((Number)x.get("attempts")).doubleValue()).average().orElse(0);
        return new Signal(observations.size(),errors,sorted[Math.max(0,(int)Math.ceil(sorted.length*.95)-1)],attempts);
    }
}
