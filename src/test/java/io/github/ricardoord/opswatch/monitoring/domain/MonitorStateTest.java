package io.github.ricardoord.opswatch.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.domain.StateChange.Transition;
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

    @Test
    void aCheckBecomesTheLastResultAndMovesTheStatus() {
        MonitorState state = MonitorState.pending(UUID.randomUUID(), Duration.ZERO, CLOCK);
        Instant checkedAt = NOW_IN_MICROS.plusSeconds(30);

        Transition transition = state.record(
                CheckOutcome.down(FailureReason.TIMEOUT, null, null, "no response within the timeout"),
                thresholds(1, 1),
                checkedAt,
                LATER);

        assertThat(transition).isEqualTo(Transition.WENT_DOWN);
        assertThat(state.status()).isEqualTo(MonitorStatus.DOWN);
        assertThat(state.statusChangedAt()).isEqualTo(checkedAt);
        assertThat(state.consecutiveFailures()).isEqualTo(1);
        assertThat(state.lastCheckedAt()).isEqualTo(checkedAt);
        assertThat(state.lastCheckStatus()).isEqualTo(CheckStatus.DOWN);
        assertThat(state.lastFailureReason()).isEqualTo(FailureReason.TIMEOUT);
        assertThat(state.lastHttpStatus()).isNull();
        assertThat(state.lastResponseTimeMs()).isNull();
        assertThat(state.updatedAt()).isEqualTo(LATER.instant());
        assertThat(state.nextCheckAt()).isEqualTo(NOW_IN_MICROS);
    }

    /** The status began with the check that changed it, not with the last one. */
    @Test
    void aCheckThatKeepsTheStatusKeepsWhenItBegan() {
        MonitorState state = MonitorState.pending(UUID.randomUUID(), Duration.ZERO, CLOCK);
        Instant first = NOW_IN_MICROS.plusSeconds(30);
        Instant second = first.plusSeconds(60);

        state.record(CheckOutcome.up(200, Duration.ofMillis(143)), thresholds(3, 2), first, LATER);
        state.record(CheckOutcome.up(204, Duration.ofMillis(87)), thresholds(3, 2), second, LATER);

        assertThat(state.status()).isEqualTo(MonitorStatus.UP);
        assertThat(state.statusChangedAt()).isEqualTo(first);
        assertThat(state.consecutiveSuccesses()).isEqualTo(2);
        assertThat(state.lastCheckedAt()).isEqualTo(second);
        assertThat(state.lastHttpStatus()).isEqualTo(204);
        assertThat(state.lastResponseTimeMs()).isEqualTo(87);
        assertThat(state.lastFailureReason()).isNull();
    }

    /** The check was in flight when the monitor was paused, or deleted. */
    @ParameterizedTest
    @EnumSource(CheckStatus.class)
    void theResultOfAPausedMonitorChangesNothing(CheckStatus result) {
        MonitorState state = MonitorState.pending(UUID.randomUUID(), Duration.ZERO, CLOCK);
        state.pause(LATER);

        Transition transition =
                state.record(outcome(result), thresholds(1, 1), LATER.instant().plusSeconds(1), LATER);

        assertThat(transition).isEqualTo(Transition.NONE);
        assertThat(state.status()).isEqualTo(MonitorStatus.PAUSED);
        assertThat(state.consecutiveFailures()).isZero();
        assertThat(state.consecutiveSuccesses()).isZero();
        assertThat(state.lastCheckedAt()).isNull();
    }

    /**
     * Paused and resumed while a check was in flight: its result belongs to the monitor before the resume, and with a
     * threshold of 1 it would open an incident for a monitor that has not been checked since.
     */
    @Test
    void aCheckThatStartedBeforeAResumeChangesNothing() {
        MonitorState state = MonitorState.pending(UUID.randomUUID(), Duration.ZERO, CLOCK);
        Instant inFlightSince = LATER.instant().minusSeconds(5);
        state.pause(LATER);
        state.resume(Duration.ofSeconds(10), Clock.offset(LATER, Duration.ofSeconds(2)));

        Transition transition = state.record(
                CheckOutcome.down(FailureReason.CONNECTION_FAILED, null, null, "could not connect"),
                thresholds(1, 1),
                inFlightSince,
                Clock.offset(LATER, Duration.ofSeconds(3)));

        assertThat(transition).isEqualTo(Transition.NONE);
        assertThat(state.status()).isEqualTo(MonitorStatus.PENDING);
        assertThat(state.consecutiveFailures()).isZero();
        assertThat(state.lastCheckedAt()).isNull();
    }

    private static CheckOutcome outcome(CheckStatus status) {
        return switch (status) {
            case UP -> CheckOutcome.up(200, Duration.ofMillis(100));
            case DEGRADED -> CheckOutcome.degraded(200, Duration.ofMillis(2000));
            case DOWN -> CheckOutcome.down(FailureReason.UNEXPECTED_STATUS, 503, Duration.ofMillis(100), null);
        };
    }

    private static MonitorSettings thresholds(int failure, int recovery) {
        return new MonitorSettings(ProbeMethod.GET, 200, 299, 60, 10_000, null, true, failure, recovery);
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
