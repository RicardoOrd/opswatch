package io.github.ricardoord.opswatch.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

class DeploymentGuardrailsTest {

    private static final String[] SAFE_DEPLOYED_CONFIG = {
        "spring.datasource.url=jdbc:postgresql://db:5432/opswatch",
        "spring.datasource.password=from-a-secret",
        "opswatch.security.jwt.issuer=https://opswatch.example.com",
        "server.forward-headers-strategy=native",
        "server.tomcat.remoteip.internal-proxies=172.30.0.2/32",
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
    void requiresAnHttpsIssuer() {
        assertFailsWith(
                "opswatch.security.jwt.issuer must be an https URL",
                "opswatch.security.jwt.issuer=http://opswatch.example.com");
    }

    @Test
    void rejectsTheCheapBcryptCostOfTheTests() {
        assertFailsWith(
                "opswatch.security.password.bcrypt-strength must be at least 12",
                "opswatch.security.password.bcrypt-strength=4");
    }

    @Test
    void requiresNativeForwardedHeaders() {
        // "framework" would take X-Forwarded-For from any client
        assertFailsWith(
                "server.forward-headers-strategy must be 'native'", "server.forward-headers-strategy=framework");
    }

    @Test
    void requiresTheReverseProxyAsTheOnlyTrustedProxy() {
        // Tomcat's default trusts every private address; without "/" the value would be read as a regex
        assertFailsWith(
                "server.tomcat.remoteip.internal-proxies must be the address of the reverse proxy",
                "server.tomcat.remoteip.internal-proxies=");
        assertFailsWith(
                "server.tomcat.remoteip.internal-proxies must be the address of the reverse proxy",
                "server.tomcat.remoteip.internal-proxies=172\\.30\\.0\\.2");
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
        // The guardrails only run under "deployed": if the group in application.yml broke, they would silently stop.
        // Read statically: starting an application with the production profile would switch the whole test JVM to
        // JSON logging.
        Properties properties = yaml("application.yml");

        assertThat(properties.getProperty("spring.profiles.group.production")).isEqualTo(DeploymentGuardrails.DEPLOYED);
        assertThat(properties.getProperty("spring.profiles.group.staging")).isEqualTo(DeploymentGuardrails.DEPLOYED);
    }

    @Test
    void deployedProfileTakesTheClientAddressFromTrustedProxiesOnly() {
        Properties properties = yaml("application-deployed.yml");

        assertThat(properties.getProperty("server.forward-headers-strategy")).isEqualTo("native");
    }

    private static Properties yaml(String resource) {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(resource));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();
        return properties;
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
}
