package dev.danieloh.canary.service;

import dev.danieloh.canary.model.Evaluation;
import dev.danieloh.canary.agentic.InvestigationWorkflow;
import dev.danieloh.canary.agentic.NumericalReview;
import dev.danieloh.canary.agentic.AgentFailure;

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
                InvestigationWorkflow.Report report=null;
                for(int attempt=1;attempt<=2;attempt++) {
                    try { report=workflow.investigate(e.id); }
                    catch(RuntimeException failure) {
                        var diagnostic=AgentFailure.describe(failure,attempt,attempt==1 && e.toolCalls.get()<=6
                            && Instant.now().isBefore(e.startedAt.plusSeconds(60)) && e.status.equals("RUNNING"));
                        e.executionFailures.add(diagnostic);
                        store.event("agent-error",diagnostic.category()+": "+diagnostic.message());
                        if(!diagnostic.retryScheduled())throw failure;
                        e.numericalFeedback="The previous pass failed to return valid structured findings. Read the SAME frozen evidence and return the required JSON fields.";
                        store.event("orchestrator","Retrying recoverable agent execution once; original deadline, frozen evidence and tool budget unchanged");
                        continue;
                    }
                    var issues=NumericalReview.issues(e.evidence,report);
                    e.numericalReviews.add(new NumericalReview.Audit(attempt,issues,report));
                    if(issues.isEmpty())break;
                    e.numericalFeedback=String.join("; ",issues);
                    store.event("numerical-review","Attempt "+attempt+": "+e.numericalFeedback);
                    if(attempt==2)break;
                    store.event("orchestrator","Correcting numerical contradictions once against the same frozen evidence; deadline and tool budget unchanged");
                }
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
                if(e.executionFailures.isEmpty() || ex instanceof TimeoutException)
                    e.executionFailures.add(AgentFailure.describe(ex,1,false));
                var failure=e.executionFailures.getLast();
                e.recommendation=new Recommendation(Verdict.HOLD,"Agent execution failed: "+failure.message(),List.of(e.id),List.of(failure.category()));
                e.agents.replaceAll((k,v)->v.equals("COMPLETE")?v:"UNAVAILABLE");
                complete(e);
            } store.event("orchestrator",e.recommendation.summary());
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
    private void requirePendingApproval(Evaluation e) {
        if(store.latest()!=e || !e.status.equals("COMPLETE") || !e.profile.equals("production")
                || e.decision==null || !e.decision.approvalRequired() || e.decision.effective()!=Verdict.HOLD
                || e.recommendation==null || e.recommendation.verdict()!=Verdict.GO)
            throw new jakarta.ws.rs.ClientErrorException("No pending Production approval for this investigation",409);
        if(!Instant.now().isBefore(e.decision.expiresAt()))
            throw new jakarta.ws.rs.ClientErrorException("Approval evidence expired; run a fresh investigation",409);
    }
    public synchronized void approve(Evaluation e) {
        requirePendingApproval(e);
        e.decision=policy.evaluate(e.evidence,e.recommendation,e.profile,true,Instant.now());
        e.humanReview=new HumanReview(e.decision.effective()==Verdict.GO?"APPROVED":"APPROVAL_RECHECK_HELD",Instant.now(),"presenter");
        store.event("policy","Presenter approved " + e.id + ": " + e.decision.effective());
    }
    public synchronized void decline(Evaluation e) {
        requirePendingApproval(e);
        e.decision=new Decision(e.recommendation.verdict(),Verdict.NO_GO,
            "Presenter declined promotion of "+e.evidence.revision()+" at rollout step "+e.evidence.rolloutStep(),
            false,e.decision.expiresAt(),e.profile);
        e.humanReview=new HumanReview("DECLINED",Instant.now(),"presenter");
        store.event("policy","Presenter declined " + e.id + ": NO_GO at rollout step "+e.evidence.rolloutStep());
    }
    static List<String> validationIssues(Evaluation e) {
        List<String> issues=new ArrayList<>();
        var missingTools=List.of("metrics-tool","trace-tool","log-tool","release-tool","runbook-tool")
            .stream().filter(tool->!e.toolReads.contains(tool)).toList();
        if(!missingTools.isEmpty())issues.add("Missing mandatory tool reads: "+String.join(", ",missingTools));
        for(var role:List.of("metrics","dependency","release")) {
            var f=e.findings.get(role);
            if(f==null){issues.add("Missing "+role+" specialist finding");continue;}
            if(f.summary()==null||f.summary().isBlank())issues.add(role+" specialist returned an empty summary");
            if(f.evidenceIds()==null||f.evidenceIds().isEmpty()||f.evidenceIds().stream().anyMatch(id->!e.id.equals(id)))
                issues.add(role+" specialist has missing or invalid investigation references");
            if(f.limitations()==null)issues.add(role+" specialist did not report its limitations");
            else for(var limitation:f.limitations())issues.add(role+" specialist: "+limitation);
        }
        return List.copyOf(issues);
    }
    private void complete(Evaluation e) { synchronized(e) {
        if(e.mode.equals("live") && !e.status.equals("FAILED")) {
            var issues=validationIssues(e);
            if(!issues.isEmpty()) e.recommendation=new Recommendation(Verdict.HOLD,
                "Investigation incomplete: "+String.join("; ",issues),List.of(e.id),issues);
        }
        if(!e.status.equals("FAILED") && !e.numericalReviews.isEmpty() && !e.numericalReviews.getLast().issues().isEmpty())
            e.recommendation=new Recommendation(Verdict.HOLD,"Numerical contradictions remain after one correction attempt",List.of(e.id),e.numericalReviews.getLast().issues());
        e.decision=policy.evaluate(e.evidence,e.recommendation,e.profile,false,Instant.now());
        if(e.status.equals("FAILED") && e.decision.effective()==Verdict.HOLD)
            e.decision=new Decision(e.recommendation.verdict(),Verdict.HOLD,e.recommendation.summary(),false,e.decision.expiresAt(),e.profile);
        if(!e.status.equals("FAILED"))e.status="COMPLETE"; store.event("policy",e.decision.effective()+": "+e.decision.reason());
    }}
}
