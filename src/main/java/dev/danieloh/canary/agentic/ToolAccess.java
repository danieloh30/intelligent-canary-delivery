package dev.danieloh.canary.agentic;

import dev.danieloh.canary.service.DemoStore;
import dev.danieloh.canary.model.Evaluation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

@ApplicationScoped
public class ToolAccess {
    @Inject DemoStore store;
    public Evaluation read(String id, String tool) {
        Evaluation e = store.require(id);
        if (!e.status.equals("RUNNING") || Instant.now().isAfter(e.startedAt.plusSeconds(90)))
            throw new IllegalStateException("Investigation deadline reached");
        if (e.toolCalls.incrementAndGet() > 12) throw new IllegalStateException("Tool budget exceeded");
        e.toolReads.add(tool);
        String actor=switch(tool) {case "metrics-tool" -> "metrics";case "trace-tool","log-tool" -> "dependency";default -> "release";};
        synchronized(e) {if(e.status.equals("RUNNING"))e.agents.put(actor,"RUNNING");}
        store.event(tool, "Read frozen evidence " + id);
        return e;
    }
}
