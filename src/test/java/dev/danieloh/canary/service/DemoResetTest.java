package dev.danieloh.canary.service;

import dev.danieloh.canary.model.Evaluation;
import jakarta.ws.rs.ClientErrorException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DemoResetTest {
    static class Cluster extends ClusterService {
        boolean enabled=true,healthy;
        int faults;
        @Override public boolean isEnabled(){return enabled;}
        @Override public boolean baselineHealthy(){return healthy;}
        @Override public void fault(int seconds){assertEquals(0,seconds);faults++;}
    }
    static class Releases extends GitReleaseService {
        int commits;boolean fail;
        @Override public synchronized Map<String,String> release(String version){
            assertEquals("v1",version);commits++;
            if(fail)throw new jakarta.ws.rs.ServiceUnavailableException("Git unavailable");
            return Map.of("version","v1","releasedAt","reset-time","sha","reset-commit");
        }
    }
    Releases service(Cluster cluster){
        var s=new Releases();s.cluster=cluster;s.store=new DemoStore();s.mode="live";s.role="control";s.releaseEnabled=true;s.token=Optional.of("test-token");return s;
    }
    Evaluation previous(Releases s){
        var e=new Evaluation(RehearsalEvidence.forScene("retry"),"development","fixture");e.status="COMPLETE";s.store.add(e);return e;
    }
    @Test void healthyV1ClearsFaultAndPresentationWithoutAnotherRollout(){
        var c=new Cluster();c.healthy=true;var s=service(c);var old=previous(s);
        s.reset();assertEquals(1,c.faults);assertEquals(0,s.commits);assertNull(s.store.latest());assertNull(s.store.releaseIntent());assertSame(old,s.store.require(old.id));
    }
    @Test void pendingBaselineUsesGitAndNeverCreatesAGoDecision(){
        var c=new Cluster();var s=service(c);previous(s);
        s.reset();assertEquals(1,c.faults);assertEquals(1,s.commits);assertNull(s.store.latest());
        assertTrue(s.store.releaseIntent().reset());assertEquals("v1",s.store.releaseIntent().version());
    }
    @Test void fixtureResetNeverTouchesTheClusterOrGit(){
        var c=new Cluster();c.enabled=false;var s=service(c);s.mode="fixture";previous(s);
        s.reset();assertEquals(0,c.faults);assertEquals(0,s.commits);assertNull(s.store.latest());
    }
    @Test void activeInvestigationCannotBeReset(){
        var c=new Cluster();var s=service(c);var e=previous(s);e.status="RUNNING";
        assertThrows(ClientErrorException.class,s::reset);assertSame(e,s.store.latest());assertEquals(0,c.faults);
    }
    @Test void failedCommitDoesNotPretendTheBaselineWasRestored(){
        var c=new Cluster();var s=service(c);s.fail=true;previous(s);
        assertThrows(jakarta.ws.rs.ServiceUnavailableException.class,s::reset);assertNull(s.store.latest());assertNull(s.store.releaseIntent());assertEquals(1,c.faults);
    }
    @Test void missingCredentialsRejectBeforeChangingTheFault(){
        var c=new Cluster();var s=service(c);s.token=Optional.empty();
        assertThrows(ClientErrorException.class,s::reset);assertEquals(0,c.faults);
    }
}
