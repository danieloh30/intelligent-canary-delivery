package dev.danieloh.canary;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DemoResourceTest {
    @Test void requiresPresenterToken(){given().get("/api/state").then().statusCode(401);given().header("Authorization","Bearer wrong").get("/api/state").then().statusCode(401);}
    @Test void basicProxyAuthenticationIsDisabledByDefault(){
        given().auth().preemptive().basic("admin","test-token").get("/api/state").then().statusCode(401);
    }
    @Test void forwardedIdentityAloneCannotAuthenticate(){
        given().header("X-Forwarded-User","admin").header("X-Forwarded-Groups","canary-demo-viewers")
            .get("/api/state").then().statusCode(401);
    }
    @Test void exposesExplicitFixtureMode(){given().header("Authorization","Bearer test-token").get("/api/state").then().statusCode(200).body("mode",is("fixture")).body("clusterEnabled",is(false));}
    @Test void fixturesCanNeverAuthorizeArgo(){given().header("Authorization","Bearer test-token").get("/api/analysis?revision=v3").then().statusCode(200).body("verdict",is("HOLD"));}
    @Test void invalidInputsAreRejected(){given().header("Authorization","Bearer test-token").post("/api/evaluate?scene=unknown").then().statusCode(400);given().header("Authorization","Bearer test-token").get("/api/logs/not-a-source").then().statusCode(400);}
    @Test void servesSpa(){given().get("/").then().statusCode(200).body(containsString("The decision room"));}
    @Test void retryFixtureFinishesWithNoGo() throws Exception {
        String id=given().header("Authorization","Bearer test-token").post("/api/evaluate?scene=retry&revision=v3").then().statusCode(200).extract().path("id");
        String result="";for(int i=0;i<100;i++){result=given().header("Authorization","Bearer test-token").get("/api/evaluations/"+id).then().statusCode(200).extract().path("decision.effective");if(result!=null)break;Thread.sleep(20);}
        assertEquals("NO_GO",result);
        given().header("Authorization","Bearer test-token").get("/api/state").then().statusCode(200)
            .body("latest.id",is(id)).body("latest.decision.effective",is("NO_GO"));
    }
}
