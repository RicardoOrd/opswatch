package io.github.ricardoord.opswatch.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** A wrong retention stops the application, naming the property, instead of purging the wrong rows. */
class CheckRetentionPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CheckRetentionProperties.class)
    static class Retention {}

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Retention.class);

    @Test
    void defaultsToTheCatalog() {
        runner.run(context -> {
            CheckRetentionProperties properties = context.getBean(CheckRetentionProperties.class);
            assertThat(properties.checks()).hasDays(30);
            assertThat(properties.batchSize()).isEqualTo(10_000);
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "opswatch.retention.checks=0d",
                "opswatch.retention.checks=-1d",
                "opswatch.retention.batch-size=0"
            })
    void refusesToStartWithAnImpossibleSetting(String setting) {
        String property = setting.substring(0, setting.indexOf('='));
        runner.withPropertyValues(setting)
                .run(context ->
                        assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(property));
    }
}
