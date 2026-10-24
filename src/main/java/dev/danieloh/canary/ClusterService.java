package dev.danieloh.canary;

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
    public Map<String,Object> status() {
        if(!enabled)return Map.of("connected",false,"message","Offline rehearsal; no cluster actions");
        try {
            var r=rollout();
            var app=kube.genericKubernetesResources(context("applications","Application"))
                .inNamespace(gitopsNamespace).withName("canary-checkout").get();
            return Map.of("connected",true,"namespace",namespace,"rollout",r==null?Map.of():r.getAdditionalProperties(),
                "application",app==null?Map.of():app.getAdditionalProperties());
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
                String text=kube.pods().inNamespace(ns).withName(pod.getMetadata().getName()).tailingLines(50).getLog();
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
