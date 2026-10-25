package dev.danieloh.canary.agentic;

import dev.danieloh.canary.service.DemoStore;

import dev.danieloh.canary.model.Domain.*;
import dev.langchain4j.agentic.declarative.*;
import dev.langchain4j.service.V;
import dev.langchain4j.service.MemoryId;
import io.quarkus.arc.Arc;

/** Bounded, declarative sequence containing a parallel specialist stage. */
public interface InvestigationWorkflow {
    record Report(Finding metrics, Finding dependency, Finding release, Recommendation recommendation) {}
    record Research(Finding dependency, Finding release) {}

    @SequenceAgent(outputKey="report", subAgents={MetricsAnalyst.class, ResearchTeam.class, CanaryDecisionAgent.class})
    Report investigate(@MemoryId @V("evaluationId") String evaluationId);

    @Output
    static Report report(@V("metricsFinding") Finding metricsFinding,
                         @V("dependencyFinding") Finding dependencyFinding,
                         @V("releaseFinding") Finding releaseFinding,
                         @V("recommendation") Recommendation recommendation) {
        return new Report(metricsFinding,dependencyFinding,releaseFinding,recommendation);
    }

    interface ResearchTeam {
        @ParallelAgent(outputKey="research",subAgents={DependencyInvestigator.class,ReleaseAnalyst.class})
        Research investigate(@V("evaluationId") String evaluationId,@V("metricsFinding") Finding metricsFinding);

        @Output
        static Research findings(@V("evaluationId") String evaluationId,
                                 @V("metricsFinding") Finding metricsFinding,
                                 @V("dependencyFinding") Finding dependencyFinding,
                                 @V("releaseFinding") Finding releaseFinding) {
            var store=Arc.container().instance(DemoStore.class).get();
            var e=store.require(evaluationId);
            synchronized(e) {
                if(e.status.equals("RUNNING")) {
                    e.findings.put("metrics",metricsFinding);
                    e.findings.put("dependency",dependencyFinding);
                    e.findings.put("release",releaseFinding);
                    for(var name:java.util.List.of("metrics","dependency","release"))e.agents.put(name,"COMPLETE");
                    e.agents.put("decision","RUNNING");
                    store.event("decision","Synthesizing specialist evidence");
                }
            }
            return new Research(dependencyFinding,releaseFinding);
        }
    }
}
