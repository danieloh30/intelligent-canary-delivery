package dev.danieloh.canary.resource;

import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Provider
public class ApiSecurity implements ContainerRequestFilter {
    @ConfigProperty(name="demo.api-token",defaultValue="") String token;
    @ConfigProperty(name="demo.proxy-auth-enabled",defaultValue="false") boolean proxyAuth;
    @Override public void filter(ContainerRequestContext context) {
        String path=context.getUriInfo().getPath().replaceFirst("^/", "");
        if (!(path.startsWith("api/") || path.startsWith("internal/"))) return;
        String auth=context.getHeaderString("Authorization");
        boolean valid=!token.isBlank() && auth!=null && MessageDigest.isEqual(
            ("Bearer "+token).getBytes(StandardCharsets.UTF_8),auth.getBytes(StandardCharsets.UTF_8));
        if(!valid && proxyAuth && !token.isBlank() && auth!=null && auth.startsWith("Basic ")) {
            try {
                String credential=new String(java.util.Base64.getDecoder().decode(auth.substring(6)),StandardCharsets.UTF_8);
                int separator=credential.indexOf(':');
                valid=separator>0 && !credential.substring(0,separator).isBlank() && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                    credential.substring(separator+1).getBytes(StandardCharsets.UTF_8));
            }catch(IllegalArgumentException ignored){/* Malformed credentials remain unauthorized. */}
        }
        if (!valid) context.abortWith(Response.status(401).entity(java.util.Map.of("error","Presenter token required")).build());
    }
}
