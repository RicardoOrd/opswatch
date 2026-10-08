package io.github.ricardoord.opswatch.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** A wrong delivery setting stops the application, naming the property, instead of being ignored. */
class DeliveryPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({DeliveryProperties.class, TestNotificationProperties.class})
    static class Delivery {}

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Delivery.class);

    @Test
    void defaultsToTheCatalog() {
        runner.run(context -> {
            DeliveryProperties properties = context.getBean(DeliveryProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.pollInterval()).hasSeconds(5);
            assertThat(properties.batchSize()).isEqualTo(50);
            assertThat(properties.maxAttempts()).isEqualTo(6);
            assertThat(properties.backoff())
                    .containsExactly(
                            Duration.ZERO,
                            Duration.ofSeconds(30),
                            Duration.ofMinutes(2),
                            Duration.ofMinutes(10),
                            Duration.ofMinutes(30),
                            Duration.ofHours(1));
            assertThat(properties.lease()).hasMinutes(5);
            assertThat(context.getBean(TestNotificationProperties.class).rateLimit())
                    .hasToString("5/PT1M");
        });
    }

    /** The first wait is before the first attempt: each later one follows the attempt before it. */
    @Test
    void theBackoffGivesTheWaitBeforeEachAttempt() {
        DeliveryProperties properties = new DeliveryProperties(
                true,
                Duration.ofSeconds(5),
                50,
                3,
                List.of(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofMinutes(2)),
                Duration.ofMinutes(5));

        assertThat(properties.firstWait()).hasSeconds(1);
        assertThat(properties.waitAfter(1)).hasSeconds(30);
        assertThat(properties.waitAfter(2)).hasMinutes(2);
        assertThat(properties.isLast(2)).isFalse();
        assertThat(properties.isLast(3)).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> properties.waitAfter(3));
    }

    @Test
    void refusesABackoffThatDoesNotHaveOneWaitPerAttempt() {
        runner.withPropertyValues("opswatch.notification.delivery.max-attempts=7")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining(
                                "opswatch.notification.delivery.backoff must have one wait per attempt"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "opswatch.notification.delivery.poll-interval=0s",
                "opswatch.notification.delivery.batch-size=0",
                "opswatch.notification.delivery.lease=0s",
                "opswatch.notification.delivery.backoff=0s,-1s,2m,10m,30m,1h",
                "opswatch.notification.test.rate-limit=0/1m"
            })
    void refusesToStartWithAWrongValue(String property) {
        runner.withPropertyValues(property)
                .run(context -> assertThat(context.getStartupFailure())
                        .hasStackTraceContaining(property.substring(0, property.indexOf('='))));
    }
}
