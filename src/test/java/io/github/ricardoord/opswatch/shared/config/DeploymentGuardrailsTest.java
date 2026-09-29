package io.github.ricardoord.opswatch.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DeploymentGuardrailsTest {

    private static final String[] SAFE_DEPLOYED_CONFIG = {
        "spring.datasource.url=jdbc:postgresql://db:5432/opswatch", "spring.datasource.password=from-a-secret",
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withBean(DeploymentGuardrails.class);

    @Test
    void ignoresNonDeployedProfiles() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("opswatch.security.cors.allowed-origins=*", "spring.jpa.hibernate.ddl-auto=update")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void startsProductionWithSafeConfiguration() {
        production()
                .withPropertyValues(SAFE_DEPLOYED_CONFIG)
                .withPropertyValues("springdoc.swagger-ui.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsWildcardCors() {
        assertFailsWith(
                "opswatch.security.cors.allowed-origins must not contain '*'",
                "opswatch.security.cors.allowed-origins=https://app.example.com, *");
    }

    @Test
    void rejectsSchemaChangingDdlAuto() {
        assertFailsWith("spring.jpa.hibernate.ddl-auto must be", "spring.jpa.hibernate.ddl-auto=update");
    }

    @Test
    void rejectsFlywayCleanEnabled() {
        assertFailsWith("spring.flyway.clean-disabled must be true", "spring.flyway.clean-disabled=false");
    }

    @Test
    void requiresDatabaseCredentials() {
        production()
                .withPropertyValues("springdoc.swagger-ui.enabled=false")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasMessageContaining("spring.datasource.url must be set")
                        .hasMessageContaining("spring.datasource.password must be set"));
    }

    @Test
    void rejectsSwaggerInProductionUnlessExplicitlyPublic() {
        production()
                .withPropertyValues(SAFE_DEPLOYED_CONFIG)
                .run(context -> assertThat(context.getStartupFailure()).hasMessageContaining("Swagger UI"));

        production()
                .withPropertyValues(SAFE_DEPLOYED_CONFIG)
                .withPropertyValues("opswatch.api.docs-public=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsPrivateEgressRangesInProduction() {
        assertFailsWith(
                "opswatch.egress.allowed-private-cidrs must be empty",
                "opswatch.egress.allowed-private-cidrs=10.0.0.0/8");
    }

    @Test
    void productionAndStagingActivateTheDeployedGroup() {
        // The guardrails only run under "deployed": if the group in application.yml broke, they would silently stop
        for (String profile : new String[] {"production", "staging"}) {
            try (var context = new SpringApplicationBuilder(EmptyConfiguration.class)
                    .web(WebApplicationType.NONE)
                    .profiles(profile)
                    .run()) {
                assertThat(context.getEnvironment().getActiveProfiles()).contains(profile, "deployed");
            }
        }
    }

    private ApplicationContextRunner production() {
        return runner.withInitializer(
                context -> context.getEnvironment().setActiveProfiles("production", DeploymentGuardrails.DEPLOYED));
    }

    private void assertFailsWith(String expectedMessage, String... badProperties) {
        production()
                .withPropertyValues(SAFE_DEPLOYED_CONFIG)
                .withPropertyValues("springdoc.swagger-ui.enabled=false")
                .withPropertyValues(badProperties)
                .run(context -> assertThat(context.getStartupFailure()).hasMessageContaining(expectedMessage));
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {}
}
