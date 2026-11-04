package dev.danieloh.canary.agentic;

import dev.danieloh.canary.model.Domain.*;
import dev.danieloh.canary.model.Evaluation;
import java.util.*;
import java.util.regex.Pattern;

/** Arithmetic belongs to Java; an LLM still interprets causes and recommends an action. */
public final class NumericalReview {
    private NumericalReview() {}
    public record Check(double actual, double limit, String unit, boolean passes, String comparison) {}
    public record Audit(int attempt, List<String> issues, InvestigationWorkflow.Report report) {}

    private static Check minimum(long count) {
        return new Check(count,30,"requests",count>=30,count+" requests "+(count>=30?">=":"<")+" 30 requests");
    }
    private static Check latency(double value) {
        return new Check(value,500,"ms",value<=500,value+" ms "+(value<=500?"<=":">")+" 500 ms");
    }
    public static Map<String,Object> facts(Evaluation e) {
        var v=e.evidence;
        return Map.of("stableSamples",minimum(v.stable().requests()),"canarySamples",minimum(v.canary().requests()),
            "stableLatency",latency(v.stable().p95Ms()),"canaryLatency",latency(v.canary().p95Ms()),
            "canaryRetryBudgetExceeded",v.canary().attemptsPerCheckout()>2 && v.canary().attemptsPerCheckout()>v.stable().attemptsPerCheckout()*2,
            "instruction","Use these computed comparisons. A measured regression is a finding, not missing evidence. Relative differences below 500 ms alone do not breach the latency limit.",
            "correction",e.numericalFeedback);
    }
    private static final Pattern SAMPLE=Pattern.compile("(?i)(insufficient.{0,45}(requests|samples|observations)|(?:requests|samples|observations).{0,70}(?:does not meet|below|less than|insufficient|not enough))");
    private static final Pattern LATENCY=Pattern.compile("(?i)(?:latency|p95).{0,100}(?:exceed\\w*|above|breach\\w*|higher than).{0,55}(?:500|threshold|limit)");
    public static List<String> issues(Evidence e, InvestigationWorkflow.Report report) {
        List<String> issues=new ArrayList<>();
        List<String> claims=new ArrayList<>();
        for(var finding:Arrays.asList(report.metrics(),report.dependency(),report.release())) {
            if(finding!=null){claims.add(finding.summary());if(finding.limitations()!=null)claims.addAll(finding.limitations());}
        }
        var r=report.recommendation();
        if(r!=null){claims.add(r.summary());if(r.limitations()!=null)claims.addAll(r.limitations());}
        for(var claim:claims) {
            if(claim==null)continue;
            // Ignore negated/conditional assertions rather than reinterpret cautious prose as authority.
            for(var sentence:claim.split("[.!?](?:\\s|$)")) {
                String lower=sentence.toLowerCase(Locale.ROOT);
                if(lower.matches(".*\\b(no|not exceeding|do not exceed|does not exceed|without|if|when)\\b.*"))continue;
                if(e.stable().requests()>=30 && e.canary().requests()>=30 && SAMPLE.matcher(sentence).find())
                    issues.add("Sample contradiction: stable="+e.stable().requests()+", canary="+e.canary().requests()+"; BOTH >= 30. Claim: "+sentence);
                if(e.stable().p95Ms()<=500 && e.canary().p95Ms()<=500 && LATENCY.matcher(sentence).find())
                    issues.add("Latency contradiction: stable="+e.stable().p95Ms()+" ms, canary="+e.canary().p95Ms()+" ms; BOTH <= 500 ms. Claim: "+sentence);
            }
        }
        return List.copyOf(new LinkedHashSet<>(issues));
    }
}
