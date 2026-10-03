package io.github.ricardoord.opswatch.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.shared.error.ConflictException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class MonitorStateTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00.123456789Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Instant NOW_IN_MICROS = Instant.parse("2026-10-03T10:00:00.123456Z");
    private static final Clock LATER = Clock.fixed(Instant.parse("2026-10-03T11:00:00Z"), ZoneOffset.UTC);

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

    @Test
    void aPauseUnschedulesItAndResetsTheCounters() {
        MonitorState state = afterSomeChecks();

        state.pause(LATER);

        assertThat(state.status()).isEqualTo(MonitorStatus.PAUSED);
        assertThat(state.statusChangedAt()).isEqualTo(LATER.instant());
        assertThat(state.nextCheckAt()).isNull();
        assertThat(state.consecutiveFailures()).isZero();
        assertThat(state.consecutiveSuccesses()).isZero();
        assertThat(state.updatedAt()).isEqualTo(LATER.instant());
    }

    @Test
    void pausingWhatIsPausedIsAConflict() {
        MonitorState state = MonitorState.pending(UUID.randomUUID(), Duration.ZERO, CLOCK);
        state.pause(CLOCK);

        assertThatThrownBy(() -> state.pause(LATER))
                .isInstanceOf(ConflictException.class)
                .hasMessage("The monitor is already paused.");
    }

    @Test
    void resumingSchedulesItAsANewMonitor() {
        MonitorState state = afterSomeChecks();
        state.pause(CLOCK);

        state.resume(Duration.ofSeconds(7), LATER);

        assertThat(state.status()).isEqualTo(MonitorStatus.PENDING);
        assertThat(state.statusChangedAt()).isEqualTo(LATER.instant());
        assertThat(state.nextCheckAt()).isEqualTo(LATER.instant().plusSeconds(7));
        assertThat(state.consecutiveFailures()).isZero();
        // A paused state with a next check would break ck_monitor_state_paused: only resuming schedules it
        state.intervalChanged(30, LATER);
        assertThat(state.nextCheckAt()).isEqualTo(LATER.instant().plusSeconds(7));
    }

    @ParameterizedTest
    @EnumSource(
            value = MonitorStatus.class,
            names = {"PAUSED"},
            mode = EnumSource.Mode.EXCLUDE)
    void resumingWhatIsNotPausedIsAConflict(MonitorStatus status) {
        MonitorState state = MonitorState.pending(UUID.randomUUID(), Duration.ZERO, CLOCK);
        ReflectionTestUtils.setField(state, "status", status);

        assertThatThrownBy(() -> state.resume(Duration.ZERO, LATER))
                .isInstanceOf(ConflictException.class)
                .hasMessage("The monitor is not paused.");
        assertThat(state.status()).isEqualTo(status);
    }

    /** Deleting stops it whatever it was doing, paused included: it must never be checked again. */
    @Test
    void stoppingForADeletionWorksFromAnyStatus() {
        MonitorState running = afterSomeChecks();
        MonitorState paused = MonitorState.pending(UUID.randomUUID(), Duration.ZERO, CLOCK);
        paused.pause(CLOCK);

        running.stop(LATER);
        paused.stop(LATER);

        for (MonitorState state : List.of(running, paused)) {
            assertThat(state.status()).isEqualTo(MonitorStatus.PAUSED);
            assertThat(state.nextCheckAt()).isNull();
            assertThat(state.consecutiveFailures()).isZero();
        }
        assertThat(paused.statusChangedAt()).isEqualTo(NOW_IN_MICROS);
        assertThat(running.statusChangedAt()).isEqualTo(LATER.instant());
    }

    /** As the engine would leave it: down after a few failures. */
    private static MonitorState afterSomeChecks() {
        MonitorState state = MonitorState.pending(UUID.randomUUID(), Duration.ofSeconds(20), CLOCK);
        ReflectionTestUtils.setField(state, "status", MonitorStatus.DOWN);
        ReflectionTestUtils.setField(state, "consecutiveFailures", 4);
        ReflectionTestUtils.setField(state, "consecutiveSuccesses", 1);
        return state;
    }
}
