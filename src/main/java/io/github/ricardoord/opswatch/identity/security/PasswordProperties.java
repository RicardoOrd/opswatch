package io.github.ricardoord.opswatch.identity.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Password hashing cost. 12 everywhere except in the tests, which use 4 for speed; DeploymentGuardrails refuses a
 * deployed environment below 12. See docs/devops/environments.md#seguridad.
 */
@ConfigurationProperties("opswatch.security.password")
public record PasswordProperties(@DefaultValue("12") int bcryptStrength) {}
