package dev.danieloh.canary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.HttpException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Presenter-only Git writes and bounded Application refresh. Never exposed as an agent tool. */
@ApplicationScoped
public class GitReleaseService {
    static final String PATH = "deploy/gitops/rollout.yaml";
    @Inject ObjectMapper json;
    @Inject DemoStore store;
    @Inject ClusterService cluster;
    @ConfigProperty(name="demo.release-enabled",defaultValue="false") boolean releaseEnabled;
    @ConfigProperty(name="demo.github-token") Optional<String> token;
    @ConfigProperty(name="demo.release-repository",defaultValue="danieloh30/intelligent-canary-delivery") String repository;
    @ConfigProperty(name="demo.release-branch",defaultValue="main") String branch;
    @ConfigProperty(name="demo.release-api-url",defaultValue="https://api.github.com") String apiUrl;
    @ConfigProperty(name="demo.role",defaultValue="control") String role;
    @ConfigProperty(name="demo.ai-mode",defaultValue="live") String mode;

    public boolean enabled() {
        return releaseEnabled && role.equals("control") && mode.equals("live") && token.filter(t->!t.isBlank()).isPresent();
    }

    public synchronized Map<String,String> reset() {
        if(store.hasRunningEvaluation())throw new ClientErrorException("Wait for the current investigation before resetting",409);
        if(!cluster.isEnabled()) {
            store.resetPresentation();
            return Map.of("version","v1","message","Rehearsal reset. No cluster actions were taken.");
        }
        if(!mode.equals("live"))throw new ClientErrorException("Fixture mode cannot reset a live cluster",409);
        boolean baseline=cluster.baselineHealthy();
        if(!baseline && !enabled())throw new ClientErrorException("Configure GitHub releases before restoring the cluster baseline",409);
        cluster.fault(0);
        // Clearing the fault changes the conditions even if a subsequent Git request fails.
        store.invalidateLatest();
        if(baseline) {
            store.resetPresentation();
            return Map.of("version","v1","message","Fault cleared. Healthy v1 baseline retained; dashboard reset.");
        }
        var result=release("v1");
        store.resetPresentation();
        store.released("v1",result.get("releasedAt"),true);
        store.event("presenter","Reset requested v1 through Git. Run fresh Development investigations at the 10% and 50% analysis stages; rollout gates remain active.");
        return result;
    }

    public synchronized Map<String,String> release(String version) {
        if(!Set.of("v1","v2","v3").contains(version)) throw new BadRequestException("Expected v1, v2, or v3");
        if(!enabled()) throw new ClientErrorException("Dashboard releases are disabled. Configure the server GitHub credential or use ./demo.sh release.",409);
        if(store.hasRunningEvaluation()) throw new ClientErrorException("Wait for the current investigation before releasing",409);
        try {
            var github=new GitHubBuilder().withEndpoint(apiUrl).withOAuthToken(token.orElseThrow()).build();
            var repo=github.getRepository(repository);
            var file=repo.getFileContent(PATH,branch);
            ObjectNode manifest;
            try(var input=file.read()) { manifest=(ObjectNode)json.readTree(input); }
            Instant releasedAt=Instant.now();
            updateManifest(manifest,version,releasedAt);
            var result=repo.createContent().path(PATH).branch(branch).sha(file.getSha())
                .content((json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest)+"\n").getBytes(StandardCharsets.UTF_8))
                .message("Release checkout "+version).commit();
            String sha=result.getCommit().getSHA1();
            String url="https://github.com/"+repository+"/commit/"+sha;
            store.released(version,releasedAt.toString(),false);
            boolean refreshed=cluster.requestReleaseRefresh();
            store.event("presenter","Released "+version+" through Git: "+sha+(refreshed?". Immediate Argo CD refresh requested.":". Immediate refresh unavailable; waiting for automatic reconciliation.")+" Run an investigation after canary observations arrive.");
            return Map.of("version",version,"sha",sha,"url",url,"releasedAt",releasedAt.toString(),"refreshRequested",Boolean.toString(refreshed));
        } catch(HttpException ex) {
            if(ex.getResponseCode()==409 || ex.getResponseCode()==422)
                throw new ClientErrorException("Git changed or branch rules rejected the commit. Refresh and check the repository before retrying.",409);
            throw new ServiceUnavailableException("GitHub release unavailable. Check the server credential and repository permissions.");
        } catch(IOException ex) {
            // Never relay upstream bodies or credentials to the browser/logs.
            throw new ServiceUnavailableException("GitHub release unavailable. Check Git before retrying; a commit may already have completed.");
        }
    }

    static void updateManifest(ObjectNode manifest,String version,Instant now) {
        var template=manifest.path("spec").path("template");
        boolean found=false;
        for(var env:template.path("spec").path("containers").get(0).path("env")) {
            if(env.path("name").asText().equals("DEMO_RELEASE")) { ((ObjectNode)env).put("value",version); found=true; }
        }
        if(!found) throw new IllegalStateException("Rollout is missing DEMO_RELEASE");
        ((ObjectNode)template.path("metadata")).withObject("/annotations").put("demo.canary/released-at",now.toString());
        for(var step:manifest.path("spec").path("strategy").path("canary").path("steps"))
            for(var arg:step.path("analysis").path("args"))
                if(arg.path("name").asText().equals("release")) ((ObjectNode)arg).put("value",version);
    }
}
