package io.github.ricardoord.opswatch.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/** A wrong webhook setting stops the application, naming the property, instead of being ignored. */
class WebhookPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebhookProperties.class)
    static class Webhooks {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Webhooks.class)
            .withPropertyValues("opswatch.notification.webhook.user-agent=OpsWatch-Webhook/test");

    @Test
    void defaultsToTheCatalog() {
        runner.run(context ->
                assertThat(context.getBean(WebhookProperties.class).timeout()).hasSeconds(5));
    }

    /** Maven puts the version in when it copies the resources: a placeholder left as it is would reach every receiver. */
    @Test
    void theUserAgentOfApplicationYmlCarriesTheVersion() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("opswatch.notification.webhook.user-agent"))
                .matches("OpsWatch-Webhook/\\d+\\.\\d+\\.\\d+\\S* \\(\\+https://github\\.com/RicardoOrd/opswatch\\)");
    }

    @Test
    void refusesToStartWithoutAUserAgentOrWithATimeoutOfZero() {
        new ApplicationContextRunner()
                .withUserConfiguration(Webhooks.class)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("opswatch.notification.webhook.user-agent"));
        runner.withPropertyValues("opswatch.notification.webhook.timeout=0s")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("opswatch.notification.webhook.timeout"));
    }
}
