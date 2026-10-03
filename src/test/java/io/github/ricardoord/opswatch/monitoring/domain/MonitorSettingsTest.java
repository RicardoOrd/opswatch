package io.github.ricardoord.opswatch.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.shared.error.InvalidFieldException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MonitorSettingsTest {

    @Test
    void theDefaultsAreValid() {
        assertThat(MonitorSettings.DEFAULTS)
                .isEqualTo(new MonitorSettings(ProbeMethod.GET, 200, 299, 60, 10_000, null, true, 3, 2));
    }

    @Test
    void acceptsEveryBoundOfEveryRange() {
        assertThatNoException()
                .isThrownBy(() -> new MonitorSettings(ProbeMethod.HEAD, 100, 100, 30, 1000, 1, false, 1, 1));
        assertThatNoException()
                .isThrownBy(() -> new MonitorSettings(ProbeMethod.GET, 599, 599, 3600, 30_000, 30_000, true, 10, 10));
        assertThatNoException()
                .isThrownBy(() -> new MonitorSettings(ProbeMethod.GET, 200, 299, 30, 29_999, 29_999, true, 3, 2));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({
        "expectedStatus.min, 99",
        "expectedStatus.min, 600",
        "expectedStatus.max, 99",
        "expectedStatus.max, 600",
        "intervalSeconds, 29",
        "intervalSeconds, 3601",
        "timeoutMs, 999",
        "timeoutMs, 30001",
        "degradedThresholdMs, 0",
        "degradedThresholdMs, 30001",
        "failureThreshold, 0",
        "failureThreshold, 11",
        "recoveryThreshold, 0",
        "recoveryThreshold, 11"
    })
    void rejectsAValueOutOfItsRangeNamingTheField(String field, int value) {
        assertInvalid(() -> withField(field, value), field, "range");
    }

    @Test
    void theTimeoutMustBeShorterThanTheInterval() {
        assertInvalid(
                () -> new MonitorSettings(ProbeMethod.GET, 200, 299, 30, 30_000, null, true, 3, 2),
                "timeoutMs",
                "not-below-interval");
        assertInvalid(
                () -> new MonitorSettings(ProbeMethod.GET, 200, 299, 30, 30_001, null, true, 3, 2),
                "timeoutMs",
                "range");
    }

    @Test
    void theDegradedThresholdCannotExceedTheTimeout() {
        assertInvalid(
                () -> new MonitorSettings(ProbeMethod.GET, 200, 299, 60, 5000, 5001, true, 3, 2),
                "degradedThresholdMs",
                "above-timeout");
    }

    @Test
    void theExpectedRangeCannotBeUpsideDown() {
        assertInvalid(
                () -> new MonitorSettings(ProbeMethod.GET, 300, 299, 60, 10_000, null, true, 3, 2),
                "expectedStatus",
                "min-above-max");
    }

    private static MonitorSettings withField(String field, int value) {
        MonitorSettings d = MonitorSettings.DEFAULTS;
        return new MonitorSettings(
                d.httpMethod(),
                field.equals("expectedStatus.min") ? value : d.expectedStatusMin(),
                field.equals("expectedStatus.max") ? value : d.expectedStatusMax(),
                field.equals("intervalSeconds") ? value : d.intervalSeconds(),
                field.equals("timeoutMs") ? value : d.timeoutMs(),
                field.equals("degradedThresholdMs") ? Integer.valueOf(value) : d.degradedThresholdMs(),
                d.followRedirects(),
                field.equals("failureThreshold") ? value : d.failureThreshold(),
                field.equals("recoveryThreshold") ? value : d.recoveryThreshold());
    }

    private static void assertInvalid(Runnable build, String field, String constraint) {
        assertThatThrownBy(build::run)
                .isInstanceOf(InvalidFieldException.class)
                .hasFieldOrPropertyWithValue("field", field)
                .hasFieldOrPropertyWithValue("constraint", constraint);
    }
}
