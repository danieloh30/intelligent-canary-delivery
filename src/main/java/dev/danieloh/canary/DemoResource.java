package dev.danieloh.canary;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Instant;
import java.util.*;
import static dev.danieloh.canary.Domain.*;

@Path("/api") @Produces(MediaType.APPLICATION_JSON)
public class DemoResource {
    @Inject DemoStore store;
    @Inject InvestigationService investigations;
    @Inject EvidenceCollector collector;
    @Inject ClusterService cluster;
    @ConfigProperty(name="demo.ai-mode",defaultValue="live") String mode;
    @ConfigProperty(name="demo.role",defaultValue="control") String role;
    private void control(){if(!role.equals("control"))throw new NotFoundException();}
    @GET @Path("/state")
    public Map<String,Object> state() {
        control();
        return Map.of("mode",mode,"model","gpt-4o-mini","clusterEnabled",cluster.enabled,
            "latest",store.latestId==null?Map.of():store.require(store.latestId),"events",store.events(),
            "keyConfigured",System.getenv("OPENAI_API_KEY")!=null);
    }
    @GET @Path("/cluster") public Map<String,Object> cluster(){control();return cluster.status();}
    @GET @Path("/logs/{source}") public List<String> logs(@PathParam("source") String source){control();return cluster.logs(source);}
    @POST @Path("/evaluate")
    public Evaluation evaluate(@QueryParam("scene") @DefaultValue("healthy") String scene,
                               @QueryParam("profile") @DefaultValue("development") String profile,
                               @QueryParam("revision") @DefaultValue("v2") String revision) {
        control();
        if(!Set.of("healthy","slowdown","retry","missing").contains(scene)||!Set.of("v1","v2","v3").contains(revision))throw new BadRequestException();
        if(store.evaluations.values().stream().anyMatch(x->x.status.equals("RUNNING")))throw new ClientErrorException("An investigation is already running",409);
        Evidence evidence=cluster.enabled?collector.collect(revision):RehearsalEvidence.forScene(scene);
        if(cluster.enabled && scene.equals("missing")) evidence=new Evidence(evidence.id(),evidence.capturedAt(),evidence.source(),evidence.revision(),evidence.stableRevision(),evidence.rolloutStep(),evidence.stable(),evidence.canary(),List.of(),evidence.logs(),List.of("Presenter-injected trace evidence outage"),evidence.releaseNotes());
        return investigations.start(evidence,profile);
    }
    @GET @Path("/evaluations/{id}") public Evaluation evaluation(@PathParam("id") String id){control();return store.require(id);}
    @POST @Path("/evaluations/{id}/approve") public Evaluation approve(@PathParam("id") String id){control();var e=store.require(id);investigations.approve(e);return e;}
    @POST @Path("/fault/{seconds}") public Map<String,Object> fault(@PathParam("seconds") int seconds){control();if(seconds<0||seconds>120)throw new BadRequestException();cluster.fault(seconds);store.event("presenter","Inventory slowdown set to "+seconds+" seconds");return Map.of("seconds",seconds);}
    @GET @Path("/analysis")
    public Map<String,Object> analysis(@QueryParam("revision") String revision) {
        control();
        if(store.latestId==null)return Map.of("verdict","HOLD","reason","Run an investigation for this release");
        var e=store.require(store.latestId);
        if(!cluster.enabled || !e.mode.equals("live") || !e.evidence.source().equals("LIVE_KUBERNETES_REQUESTS"))
            return Map.of("verdict","HOLD","reason","Offline evidence cannot control a rollout");
        if(e.decision==null||revision==null||!revision.equals(e.evidence.revision())||Instant.now().isAfter(e.decision.expiresAt()))
            return Map.of("verdict","HOLD","reason","Decision pending, stale, or belongs to another release");
        // Recheck actual pod-template hash so a previous run of v2 cannot authorize a new v2 rollout.
        try {
            var status=(Map<?,?>)cluster.rollout().getAdditionalProperties().get("status");
            String hash=Objects.toString(status.get("currentPodHash"),"");
            if(!(status.get("currentStepIndex") instanceof Number step) || step.intValue()!=e.evidence.rolloutStep())return Map.of("verdict","HOLD","reason","A fresh investigation is required at this rollout step");
            boolean matches=e.evidence.traces().stream().anyMatch(x->hash.equals(x.get("podHash")));
            if(!matches)return Map.of("verdict","HOLD","reason","Current rollout revision differs from evidence");
        }catch(Exception ex){return Map.of("verdict","HOLD","reason","Cannot verify rollout identity");}
        return Map.of("verdict",e.decision.effective().name(),"evaluationId",e.id,"reason",e.decision.reason());
    }
}
