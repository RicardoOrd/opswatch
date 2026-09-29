package io.github.ricardoord.opswatch.identity.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Browser origins allowed to call the API. Empty by default: in production the frontend is served from the same
 * origin and CORS is not needed. A wildcard is rejected in deployed environments by DeploymentGuardrails.
 */
@ConfigurationProperties("opswatch.security.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
