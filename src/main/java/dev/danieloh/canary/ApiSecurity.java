package dev.danieloh.canary;

import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Provider
public class ApiSecurity implements ContainerRequestFilter {
    @ConfigProperty(name="demo.api-token",defaultValue="") String token;
    @Override public void filter(ContainerRequestContext context) {
        String path=context.getUriInfo().getPath().replaceFirst("^/", "");
        if (!(path.startsWith("api/") || path.startsWith("internal/"))) return;
        String auth=context.getHeaderString("Authorization");
        boolean valid=!token.isBlank() && auth!=null && MessageDigest.isEqual(
            ("Bearer "+token).getBytes(StandardCharsets.UTF_8),auth.getBytes(StandardCharsets.UTF_8));
        if (!valid) context.abortWith(Response.status(401).entity(java.util.Map.of("error","Presenter token required")).build());
    }
}
