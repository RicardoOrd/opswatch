package io.github.ricardoord.opswatch.monitoring.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/** A wrong engine setting stops the application, naming the property, instead of being ignored. */
class MonitoringEnginePropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MonitoringEngineProperties.class)
    static class Engine {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Engine.class)
            .withPropertyValues("opswatch.monitoring.engine.user-agent=OpsWatch-Test/0");

    @Test
    void defaultsToTheCatalog() {
        runner.run(context -> {
            MonitoringEngineProperties properties = context.getBean(MonitoringEngineProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.dispatchInterval()).hasSeconds(1);
            assertThat(properties.maxConcurrentChecks()).isEqualTo(200);
            assertThat(properties.maxBatchSize()).isEqualTo(500);
            assertThat(properties.maxRedirects()).isEqualTo(5);
            assertThat(properties.deadlineGrace()).hasMillis(200);
            assertThat(properties.shutdownGrace()).hasSeconds(5);
        });
    }

    /** Maven puts the version in when it copies the resources: a placeholder left as it is would reach every target. */
    @Test
    void theUserAgentOfApplicationYmlCarriesTheVersion() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("opswatch.monitoring.engine.user-agent"))
                .matches("OpsWatch-Monitor/\\d+\\.\\d+\\.\\d+\\S* \\(\\+https://github\\.com/RicardoOrd/opswatch\\)");
    }

    @Test
    void refusesToStartWithoutAUserAgent() {
        new ApplicationContextRunner()
                .withUserConfiguration(Engine.class)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining("opswatch.monitoring.engine.user-agent"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "opswatch.monitoring.engine.dispatch-interval=0s",
                "opswatch.monitoring.engine.max-batch-size=0",
                "opswatch.monitoring.engine.shutdown-grace=-1s"
            })
    void refusesToStartWithAnImpossibleSetting(String setting) {
        String property = setting.substring(0, setting.indexOf('='));
        runner.withPropertyValues(setting)
                .run(context ->
                        assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(property));
    }

    @Test
    void refusesToStartWithNoRoomForAnyCheck() {
        runner.withPropertyValues("opswatch.monitoring.engine.max-concurrent-checks=0")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("opswatch.monitoring.engine.max-concurrent-checks"));
    }
}
