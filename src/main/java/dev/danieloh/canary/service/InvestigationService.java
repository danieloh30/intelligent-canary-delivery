package dev.danieloh.canary.service;

import dev.danieloh.canary.model.Evaluation;
import dev.danieloh.canary.agentic.InvestigationWorkflow;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.annotation.PreDestroy;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static dev.danieloh.canary.model.Domain.*;

@ApplicationScoped
public class InvestigationService {
    @Inject InvestigationWorkflow workflow;
    @Inject DemoStore store;
    @Inject DecisionPolicy policy;
    @ConfigProperty(name="demo.ai-mode",defaultValue="live") String aiMode;
    @ConfigProperty(name="quarkus.langchain4j.openai.api-key") String apiKey;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy void shutdown(){executor.shutdownNow();}

    public synchronized Evaluation start(Evidence evidence, String profile) {
        if(store.hasRunningEvaluation())throw new jakarta.ws.rs.ClientErrorException("An investigation is already running",409);
        if (!Set.of("development","production").contains(profile)) throw new jakarta.ws.rs.BadRequestException("Invalid profile");
        Evaluation e=new Evaluation(evidence,profile,aiMode);
        store.add(e); store.event("orchestrator","Started " + e.id + " using " + aiMode + " agents");
        executor.submit(() -> run(e));
        return e;
    }
    private void run(Evaluation e) {
        try {
            if (aiMode.equals("fixture")) { fixture(e); return; }
            if (apiKey.isBlank() || apiKey.equals("not-configured"))
                throw new IllegalStateException("OPENAI_API_KEY is required for live agents");
            Future<?> work=executor.submit(() -> {
                e.agents.put("metrics","RUNNING");
                var report=workflow.investigate(e.id);
                synchronized(e) {
                    if(!e.status.equals("RUNNING"))return;
                    e.findings.put("metrics",report.metrics());
                    e.findings.put("dependency",report.dependency());
                    e.findings.put("release",report.release());
                    e.recommendation=report.recommendation();
                    e.agents.replaceAll((k,v)->"COMPLETE");
                }
            });
            try { work.get(85,TimeUnit.SECONDS); }
            finally { if (!work.isDone()) work.cancel(true); }
            complete(e);
        } catch (Exception ex) {
            synchronized(e) {
                e.status="FAILED";
                e.recommendation=new Recommendation(Verdict.HOLD,"Investigation unavailable or deadline exceeded",List.of(e.id),List.of("Agent execution incomplete"));
                e.agents.replaceAll((k,v)->v.equals("COMPLETE")?v:"UNAVAILABLE");
                complete(e);
            } store.event("orchestrator","Investigation held: " + ex.getClass().getSimpleName());
        }
    }
    private Finding call(Evaluation e,String name,Callable<Finding> fn) {
        e.agents.put(name,"RUNNING"); store.event(name,"Investigating " + e.id);
        try { Finding result=fn.call(); synchronized(e) {
            if(!e.status.equals("RUNNING"))throw new CancellationException("Investigation already completed");
            e.findings.put(name,result); e.agents.put(name,"COMPLETE"); }
            store.event(name,result.summary()); return result; }
        catch(Exception ex) {synchronized(e){if(e.status.equals("RUNNING"))e.agents.put(name,"UNAVAILABLE");}throw new CompletionException(ex);}
    }
    private void fixture(Evaluation e) {
        // Deterministic rehearsal is deliberately labeled and cannot control a live rollout.
        boolean retry=e.evidence.canary().attemptsPerCheckout()>2;
        call(e,"metrics",()->new Finding("Offline fixture: stable/canary metrics compared",List.of(e.id),List.of()));
        call(e,"dependency",()->new Finding(retry?"Offline fixture: canary-only retry amplification":"Offline fixture: no canary-specific amplification",List.of(e.id),List.of()));
        call(e,"release",()->new Finding(e.evidence.releaseNotes(),List.of(e.id),List.of()));
        Verdict v=retry?Verdict.NO_GO:e.evidence.canary().p95Ms()>500?Verdict.HOLD:Verdict.GO;
        e.recommendation=new Recommendation(v,"Offline rehearsal outcome, not an LLM response",List.of(e.id),List.of());
        e.agents.put("decision","COMPLETE"); complete(e);
    }
    public synchronized void approve(Evaluation e) {
        if(e.decision==null || !e.decision.approvalRequired()) throw new jakarta.ws.rs.ClientErrorException("No pending approval",409);
        e.decision=policy.evaluate(e.evidence,e.recommendation,e.profile,true,Instant.now());
        store.event("policy","Presenter approved " + e.id + ": " + e.decision.effective());
    }
    private void complete(Evaluation e) { synchronized(e) {
        if(e.mode.equals("live")) {
            boolean tools=e.toolReads.containsAll(Set.of("metrics-tool","trace-tool","log-tool","release-tool","runbook-tool"));
            boolean findings=e.findings.size()==3 && e.findings.values().stream().allMatch(f ->
                f!=null && f.summary()!=null && !f.summary().isBlank() && f.evidenceIds()!=null
                && !f.evidenceIds().isEmpty() && f.evidenceIds().stream().allMatch(e.id::equals)
                && f.limitations()!=null && f.limitations().isEmpty());
            if(!tools || !findings) e.recommendation=new Recommendation(Verdict.HOLD,
                "Mandatory tool reads or valid specialist findings are missing",List.of(e.id),List.of("Incomplete investigation"));
        }
        e.decision=policy.evaluate(e.evidence,e.recommendation,e.profile,false,Instant.now());
        e.status="COMPLETE"; store.event("policy",e.decision.effective()+": "+e.decision.reason());
    }}
}
