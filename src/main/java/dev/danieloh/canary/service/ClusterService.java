package dev.danieloh.canary.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;

@ApplicationScoped
public class ClusterService {
    @Inject KubernetesClient kube;
    @Inject ObjectMapper json;
    @ConfigProperty(name="demo.namespace",defaultValue="canary-demo") String namespace;
    @ConfigProperty(name="demo.cluster-enabled",defaultValue="false") private boolean enabled;
    @ConfigProperty(name="demo.api-token",defaultValue="") String token;
    @ConfigProperty(name="demo.gitops-namespace",defaultValue="openshift-gitops") String gitopsNamespace;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public boolean isEnabled(){return enabled;}
    public static ResourceDefinitionContext context(String plural,String kind) {
        return new ResourceDefinitionContext.Builder().withGroup("argoproj.io").withVersion("v1alpha1")
            .withPlural(plural).withKind(kind).withNamespaced(true).build();
    }
    public GenericKubernetesResource rollout() {
        return kube.genericKubernetesResources(context("rollouts","Rollout")).inNamespace(namespace).withName("checkout").get();
    }
    public boolean baselineHealthy() {
        var r=rollout();
        if(r==null)return false;
        var tree=json.valueToTree(r.getAdditionalProperties());
        String hash=tree.at("/status/currentPodHash").asText();
        return !hash.isBlank() && hash.equals(tree.at("/status/stableRS").asText())
            && tree.at("/status/phase").asText().equals("Healthy")
            && version(tree).equals("v1");
    }
    private static String version(JsonNode tree) {
        for(var env:tree.at("/spec/template/spec/containers/0/env"))
            if(env.path("name").asText().equals("DEMO_RELEASE"))return env.path("value").asText();
        return "";
    }
    public Map<String,Object> status() {
        if(!enabled)return Map.of("connected",false,"message","Offline rehearsal; no cluster actions");
        try {
            var r=rollout();
            var app=kube.genericKubernetesResources(context("applications","Application"))
                .inNamespace(gitopsNamespace).withName("canary-checkout").get();
            var tree=json.valueToTree(r==null?Map.of():r.getAdditionalProperties());
            String stableHash=tree.at("/status/stableRS").asText(),stableVersion="";
            for(var pod:kube.pods().inNamespace(namespace).withLabel("app","checkout").list().getItems()) {
                if(stableHash.equals(pod.getMetadata().getLabels().get("rollouts-pod-template-hash")))
                    for(var env:pod.getSpec().getContainers().getFirst().getEnv())
                        if(env.getName().equals("DEMO_RELEASE"))stableVersion=env.getValue();
            }
            return Map.of("connected",true,"namespace",namespace,"rollout",r==null?Map.of():r.getAdditionalProperties(),
                "application",app==null?Map.of():app.getAdditionalProperties(),"stableVersion",stableVersion,"candidateVersion",version(tree));
        } catch(Exception ex) {return Map.of("connected",false,"message","Cluster read unavailable: "+ex.getClass().getSimpleName());}
    }
    public List<String> logs(String source) {
        if(!Set.of("checkout","quarkus","inventory","rollouts","argocd").contains(source))throw new jakarta.ws.rs.BadRequestException("Unknown log source");
        if(!enabled)return List.of("Cluster log stream unavailable in offline rehearsal.");
        String ns=namespace;
        String selector=switch(source) {
            case "checkout" -> "app=checkout";
            case "quarkus" -> "app=canary-control";
            case "inventory" -> "app=inventory";
            case "rollouts" -> {ns=gitopsNamespace;yield "app.kubernetes.io/name=argo-rollouts";}
            case "argocd" -> {ns=gitopsNamespace;yield "app.kubernetes.io/name=openshift-gitops-application-controller";}
            default -> throw new jakarta.ws.rs.BadRequestException("Unknown log source");
        };
        List<String> lines=new ArrayList<>();
        try {
            for(var pod:kube.pods().inNamespace(ns).withLabelSelector(selector).list().getItems().stream().limit(3).toList()) {
                var resource=kube.pods().inNamespace(ns).withName(pod.getMetadata().getName());
                // The control pod also runs oauth-proxy; Kubernetes requires a container name.
                String text=source.equals("quarkus")?resource.inContainer("canary-control").tailingLines(50).getLog():resource.tailingLines(50).getLog();
                lines.add("--- "+pod.getMetadata().getName()+" ---");
                lines.addAll(Arrays.asList(text.split("\n")));
            }
            if(lines.isEmpty()) lines.add("No pods matched this source; check controller labels in scripts/doctor.sh.");
        } catch(Exception ex){lines.add("Log access unavailable: "+ex.getClass().getSimpleName());}
        return lines.stream().limit(160).toList();
    }
    public String request(String url,String method) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .header("Authorization","Bearer "+token);
        var response=http.send(method.equals("POST")?builder.POST(HttpRequest.BodyPublishers.noBody()).build():builder.GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("Upstream returned "+response.statusCode());
        return response.body();
    }
    public void fault(int seconds) {
        if(!enabled)throw new jakarta.ws.rs.ClientErrorException("Cluster is disabled",409);
        try {request("http://inventory:8080/internal/fault/"+seconds,"POST");}
        catch(Exception ex){throw new jakarta.ws.rs.ServiceUnavailableException("Inventory fault control unavailable");}
    }
    public KubernetesClient client(){return kube;}
    public String namespace(){return namespace;}
    public ObjectMapper mapper(){return json;}
}
