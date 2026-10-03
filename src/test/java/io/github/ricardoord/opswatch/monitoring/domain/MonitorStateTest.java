package io.github.ricardoord.opswatch.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MonitorStateTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00.123456789Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Instant NOW_IN_MICROS = Instant.parse("2026-10-03T10:00:00.123456Z");

    @Test
    void aNewMonitorIsPendingWithItsFirstCheckAfterTheJitter() {
        UUID monitorId = UUID.randomUUID();

        MonitorState state = MonitorState.pending(monitorId, Duration.ofMillis(12_345), CLOCK);

        assertThat(state.monitorId()).isEqualTo(monitorId);
        assertThat(state.status()).isEqualTo(MonitorStatus.PENDING);
        assertThat(state.statusChangedAt()).isEqualTo(NOW_IN_MICROS);
        assertThat(state.nextCheckAt()).isEqualTo(NOW_IN_MICROS.plusMillis(12_345));
        assertThat(state.consecutiveFailures()).isZero();
        assertThat(state.consecutiveSuccesses()).isZero();
        assertThat(state.lastCheckedAt()).isNull();
        assertThat(state.isNew()).isTrue();
    }

    @Test
    void anIntervalShorterThanTheWaitLeftBringsTheNextCheckForward() {
        MonitorState state = MonitorState.pending(UUID.randomUUID(), Duration.ofSeconds(20), CLOCK);
        Clock fiveSecondsLater = Clock.offset(CLOCK, Duration.ofSeconds(5));

        state.intervalChanged(10, fiveSecondsLater);

        assertThat(state.nextCheckAt()).isEqualTo(NOW_IN_MICROS.plusSeconds(15));
        assertThat(state.updatedAt()).isEqualTo(NOW_IN_MICROS.plusSeconds(5));
    }

    @Test
    void anIntervalLongerThanTheWaitLeftDoesNotPutTheNextCheckOff() {
        MonitorState state = MonitorState.pending(UUID.randomUUID(), Duration.ofSeconds(20), CLOCK);

        state.intervalChanged(30, Clock.offset(CLOCK, Duration.ofSeconds(5)));

        assertThat(state.nextCheckAt()).isEqualTo(NOW_IN_MICROS.plusSeconds(20));
        assertThat(state.updatedAt()).isEqualTo(NOW_IN_MICROS);
    }
}
