package io.github.ricardoord.opswatch.monitoring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.monitoring.domain.StateChange.Transition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Every row of the table of docs/architecture/domain-model.md#monitorstate, with a failure threshold of 3 and a recovery
 * threshold of 2 unless the row says otherwise. Columns: current status, failures and successes so far, the result of
 * the check; then the status, failures and successes after it, and what it announces.
 */
class StateTransitionTest {

    @ParameterizedTest(name = "{0} ({1}, {2}) + {3} -> {4} ({5}, {6}) {7}")
    @CsvSource({
        // PENDING, UP or DEGRADED + UP: UP, without an event
        "PENDING,  0, 0, UP,       UP,       0, 1, NONE",
        "UP,       0, 7, UP,       UP,       0, 8, NONE",
        "DEGRADED, 0, 3, UP,       UP,       0, 4, NONE",
        "UP,       2, 0, UP,       UP,       0, 1, NONE",
        // PENDING, UP or DEGRADED + DEGRADED: DEGRADED, without an event
        "PENDING,  0, 0, DEGRADED, DEGRADED, 0, 1, NONE",
        "UP,       0, 5, DEGRADED, DEGRADED, 0, 6, NONE",
        "DEGRADED, 1, 0, DEGRADED, DEGRADED, 0, 1, NONE",
        // PENDING, UP or DEGRADED + DOWN below the threshold: no change of status
        "PENDING,  0, 0, DOWN,     PENDING,  1, 0, NONE",
        "UP,       1, 0, DOWN,     UP,       2, 0, NONE",
        "DEGRADED, 0, 4, DOWN,     DEGRADED, 1, 0, NONE",
        // PENDING, UP or DEGRADED + DOWN reaching the threshold: DOWN and MonitorWentDown
        "PENDING,  2, 0, DOWN,     DOWN,     3, 0, WENT_DOWN",
        "UP,       2, 0, DOWN,     DOWN,     3, 0, WENT_DOWN",
        "DEGRADED, 2, 0, DOWN,     DOWN,     3, 0, WENT_DOWN",
        // Over the threshold, after it was lowered while failing
        "UP,       5, 0, DOWN,     DOWN,     6, 0, WENT_DOWN",
        // DOWN + DOWN: DOWN
        "DOWN,     3, 0, DOWN,     DOWN,     4, 0, NONE",
        "DOWN,     3, 1, DOWN,     DOWN,     4, 0, NONE",
        // DOWN + UP or DEGRADED below the recovery threshold: still DOWN
        "DOWN,     3, 0, UP,       DOWN,     0, 1, NONE",
        "DOWN,     3, 0, DEGRADED, DOWN,     0, 1, NONE",
        // DOWN + UP or DEGRADED reaching the recovery threshold: the status of the last check and MonitorRecovered
        "DOWN,     0, 1, UP,       UP,       0, 2, RECOVERED",
        "DOWN,     0, 1, DEGRADED, DEGRADED, 0, 2, RECOVERED",
        "DOWN,     0, 4, UP,       UP,       0, 5, RECOVERED"
    })
    void followsTheTableOfTheDomainModel(
            MonitorStatus current,
            int failures,
            int successes,
            CheckStatus result,
            MonitorStatus status,
            int newFailures,
            int newSuccesses,
            Transition transition) {
        StateChange change = StateTransition.apply(current, failures, successes, result, thresholds(3, 2));

        assertThat(change).isEqualTo(new StateChange(status, newFailures, newSuccesses, transition));
    }

    /** A new monitor with a mistyped URL is an incident at once: the user has to see it. */
    @Test
    void aThresholdOfOneGoesDownOnTheFirstFailure() {
        StateChange change = StateTransition.apply(MonitorStatus.PENDING, 0, 0, CheckStatus.DOWN, thresholds(1, 1));

        assertThat(change.status()).isEqualTo(MonitorStatus.DOWN);
        assertThat(change.transition()).isEqualTo(Transition.WENT_DOWN);
    }

    @Test
    void aRecoveryThresholdOfOneRecoversOnTheFirstSuccess() {
        StateChange change = StateTransition.apply(MonitorStatus.DOWN, 9, 0, CheckStatus.UP, thresholds(3, 1));

        assertThat(change).isEqualTo(new StateChange(MonitorStatus.UP, 0, 1, Transition.RECOVERED));
    }

    /** The result of a check in flight when the monitor was paused never gets a transition. */
    @ParameterizedTest
    @EnumSource(CheckStatus.class)
    void aPausedMonitorHasNone(CheckStatus result) {
        assertThatThrownBy(() -> StateTransition.apply(MonitorStatus.PAUSED, 0, 0, result, thresholds(3, 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MonitorSettings thresholds(int failure, int recovery) {
        return new MonitorSettings(ProbeMethod.GET, 200, 299, 60, 10_000, null, true, failure, recovery);
    }
}
