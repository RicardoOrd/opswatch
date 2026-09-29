package io.github.ricardoord.opswatch.shared.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Refuses to start a deployed environment (profiles {@code staging} or {@code production}, both in the
 * {@code deployed} group) with configuration that is only acceptable for development. See
 * docs/devops/environments.md#salvaguardas-de-arranque.
 *
 * <p>Rules for secrets that do not exist yet (JWT keys, encryption keys) are added by the issues that introduce them.
 */
@Component
public class DeploymentGuardrails implements SmartInitializingSingleton {

    static final String DEPLOYED = "deployed";
    static final String PRODUCTION = "production";
    static final int MIN_BCRYPT_STRENGTH = 12;

    private final Environment environment;

    public DeploymentGuardrails(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> violations = violations();
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Unsafe configuration for a deployed environment:\n - " + String.join("\n - ", violations));
        }
    }

    List<String> violations() {
        List<String> violations = new ArrayList<>();
        if (!environment.acceptsProfiles(Profiles.of(DEPLOYED))) {
            return violations;
        }
        boolean production = environment.acceptsProfiles(Profiles.of(PRODUCTION));

        String corsOrigins = environment.getProperty("opswatch.security.cors.allowed-origins", "");
        if (Arrays.stream(corsOrigins.split(",")).map(String::trim).anyMatch("*"::equals)) {
            violations.add("opswatch.security.cors.allowed-origins must not contain '*'");
        }

        String ddlAuto = environment.getProperty("spring.jpa.hibernate.ddl-auto", "none");
        if (!ddlAuto.equals("validate") && !ddlAuto.equals("none")) {
            violations.add("spring.jpa.hibernate.ddl-auto must be 'validate' or 'none', not '" + ddlAuto + "'");
        }

        if (!environment.getProperty("spring.flyway.clean-disabled", Boolean.class, true)) {
            violations.add("spring.flyway.clean-disabled must be true");
        }

        for (String required : List.of("spring.datasource.url", "spring.datasource.password")) {
            if (!StringUtils.hasText(environment.getProperty(required))) {
                violations.add(required + " must be set");
            }
        }

        // The tests hash with cost 4 for speed: that value must never reach a real environment
        int bcryptStrength = environment.getProperty(
                "opswatch.security.password.bcrypt-strength", Integer.class, MIN_BCRYPT_STRENGTH);
        if (bcryptStrength < MIN_BCRYPT_STRENGTH) {
            violations.add("opswatch.security.password.bcrypt-strength must be at least " + MIN_BCRYPT_STRENGTH);
        }

        if (production) {
            boolean swaggerEnabled = environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class, true);
            boolean docsPublic = environment.getProperty("opswatch.api.docs-public", Boolean.class, false);
            if (swaggerEnabled && !docsPublic) {
                violations.add("Swagger UI must be disabled in production unless opswatch.api.docs-public=true");
            }
            if (StringUtils.hasText(environment.getProperty("opswatch.egress.allowed-private-cidrs"))) {
                violations.add("opswatch.egress.allowed-private-cidrs must be empty in production");
            }
        }
        return violations;
    }
}
