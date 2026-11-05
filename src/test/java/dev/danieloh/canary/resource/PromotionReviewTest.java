package dev.danieloh.canary.resource;

import dev.danieloh.canary.model.*;
import dev.danieloh.canary.service.*;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static dev.danieloh.canary.model.Domain.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class PromotionReviewTest {
    @Inject DemoStore store;
    @Inject InvestigationService investigations;
    @Inject DecisionPolicy policy;
    Evaluation pending() {
        var evidence=new Evidence(UUID.randomUUID().toString(),Instant.now(),"LIVE_KUBERNETES_REQUESTS","v2","stable",2,
            new Signal(800,0,3,1),new Signal(100,0,6,1),List.of(),List.of(),List.of(),"v2","candidate");
        var e=new Evaluation(evidence,"production","live");e.status="COMPLETE";
        e.recommendation=new Recommendation(Verdict.GO,"Eligible for promotion",List.of(e.id),List.of());
        e.decision=policy.evaluate(e.evidence,e.recommendation,e.profile,false,Instant.now());store.add(e);return e;
    }
    @Test void declineRequiresAuthentication() {
        given().post("/api/evaluations/anything/decline").then().statusCode(401);
    }
    @Test void declineRecordsHumanNoGoWithoutChangingTheAgentRecommendationOrExpiry() {
        var e=pending();var expiry=e.decision.expiresAt();
        given().header("Authorization","Bearer test-token").post("/api/evaluations/"+e.id+"/decline").then().statusCode(200)
            .body("recommendation.verdict",is("GO")).body("decision.effective",is("NO_GO"))
            .body("decision.approvalRequired",is(false)).body("humanReview.action",is("DECLINED"));
        assertEquals(expiry,e.decision.expiresAt());assertEquals("presenter",e.humanReview.actor());
        assertTrue(store.events().stream().anyMatch(x->x.message().contains("Presenter declined "+e.id)));
        given().header("Authorization","Bearer test-token").post("/api/evaluations/"+e.id+"/approve").then().statusCode(409);
        given().header("Authorization","Bearer test-token").post("/api/evaluations/"+e.id+"/decline").then().statusCode(409);
        given().header("Authorization","Bearer test-token").get("/api/analysis?revision=v2").then().body("verdict",is("HOLD"));
    }
    @Test void expiredAndSupersededReviewsCannotBeDeclined() {
        var old=pending();pending();
        given().header("Authorization","Bearer test-token").post("/api/evaluations/"+old.id+"/decline").then().statusCode(409);
        var expired=pending();expired.decision=new Decision(Verdict.GO,Verdict.HOLD,"Pending",true,Instant.now().minusSeconds(1),"production");
        given().header("Authorization","Bearer test-token").post("/api/evaluations/"+expired.id+"/decline").then().statusCode(409);
        assertNull(expired.humanReview);
    }
    @Test void noGoAndMissingEvidenceHoldsDoNotExposeAPendingHumanReview() {
        for(var scene:List.of("retry","missing")){
            var evidence=RehearsalEvidence.forScene(scene);var e=new Evaluation(evidence,"production","fixture");e.status="COMPLETE";
            e.recommendation=new Recommendation(scene.equals("retry")?Verdict.NO_GO:Verdict.HOLD,"Evidence requires stop",List.of(e.id),List.of());
            e.decision=policy.evaluate(evidence,e.recommendation,"production",false,Instant.now());store.add(e);
            given().header("Authorization","Bearer test-token").post("/api/evaluations/"+e.id+"/decline").then().statusCode(409);
        }
    }
    static class Cluster extends ClusterService {
        String hash="candidate",stable="stable",phase="Progressing";int step=2;
        @Override public boolean isEnabled(){return true;}
        @Override public GenericKubernetesResource rollout(){var r=new GenericKubernetesResource();r.setAdditionalProperty("status",Map.of("currentPodHash",hash,"stableRS",stable,"currentStepIndex",step,"phase",phase));return r;}
    }
    DemoResource resource(Cluster cluster){var r=new DemoResource();r.role="control";r.cluster=cluster;r.store=store;r.investigations=investigations;return r;}
    @Test void bothReviewActionsRejectChangedStageCandidateBaselineAndFinishedRollout() {
        for(var mismatch:List.of("stage","candidate","baseline","finished")){
            var e=pending();var c=new Cluster();switch(mismatch){case "stage"->c.step=5;case "candidate"->c.hash="new-candidate";case "baseline"->c.stable="new-stable";default->c.phase="Degraded";}
            var r=resource(c);assertThrows(ClientErrorException.class,()->r.decline(e.id),mismatch);assertThrows(ClientErrorException.class,()->r.approve(e.id),mismatch);
            assertEquals(Verdict.HOLD,e.decision.effective());assertNull(e.humanReview);
        }
    }
    @Test void validLiveDeclineIsConsumedAsNoGoOnlyByTheSameGate() {
        var e=pending();var c=new Cluster();var r=resource(c);r.decline(e.id);
        assertEquals("NO_GO",r.analysis("v2").get("verdict"));c.step=5;assertEquals("HOLD",r.analysis("v2").get("verdict"));
    }
}
