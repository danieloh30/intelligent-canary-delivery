package dev.danieloh.canary;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/** HTTP boundary tests for the explicit trusted-proxy credential mode. */
@QuarkusTest
@TestProfile(DemoResourceProxyAuthTest.ProxyProfile.class)
class DemoResourceProxyAuthTest {
    public static class ProxyProfile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {
            return Map.of("demo.proxy-auth-enabled","true");
        }
    }

    private static String basic(String credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Test void explicitProxyModeAcceptsAuthenticatedUsernameAndSharedSecret() {
        given().header("Authorization",basic("admin:test-token")).get("/api/state")
            .then().statusCode(200).body("mode",is("fixture"));
        // Human usernames come from the authenticating proxy, not a hard-coded admin identity.
        given().header("Authorization",basic("presenter@example.test:test-token")).get("/api/state")
            .then().statusCode(200).body("clusterEnabled",is(false));
    }

    @Test void wrongProxyPasswordCannotAuthenticateEvenWithSpoofedIdentity() {
        given().header("Authorization",basic("admin:wrong-token"))
            .header("X-Forwarded-User","admin").header("X-Forwarded-Groups","canary-demo-viewers")
            .get("/api/state").then().statusCode(401);
    }

    @Test void missingOrMalformedBasicCredentialsAreRejected() {
        for (String authorization : new String[]{
                "Basic not-base64!", "Basic", "Basic ", basic("admin"),
                basic(":test-token"), basic("   :test-token"), basic("admin:"), basic("")}) {
            given().header("Authorization",authorization).get("/api/state").then().statusCode(401);
        }
    }

    @Test void proxyModeDoesNotTrustForwardedIdentityWithoutCredential() {
        given().header("X-Forwarded-User","admin").header("X-Forwarded-Email","admin@example.test")
            .get("/api/state").then().statusCode(401);
    }

    @Test void bearerAuthenticationStillWorksWithProxyModeEnabled() {
        given().header("Authorization","Bearer test-token").get("/api/state")
            .then().statusCode(200).body("mode",is("fixture"));
        given().header("Authorization","Bearer wrong-token").get("/api/state").then().statusCode(401);
    }
}
