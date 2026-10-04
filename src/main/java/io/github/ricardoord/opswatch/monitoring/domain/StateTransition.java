package io.github.ricardoord.opswatch.monitoring.domain;

import io.github.ricardoord.opswatch.monitoring.domain.StateChange.Transition;

/**
 * The state machine of a monitor (docs/architecture/domain-model.md#monitorstate), as a pure function: every row of its
 * table has a test.
 *
 * <ul>
 *   <li>A {@code DOWN} check adds a failure and clears the successes; any other check, the other way round.
 *   <li>Out of {@code DOWN} only by {@code recoveryThreshold} successes in a row, into {@code DOWN} only by
 *       {@code failureThreshold} failures in a row. With {@code ≥}: lowering a threshold while a monitor fails takes
 *       effect on its next check.
 *   <li>{@code UP} and {@code DEGRADED} switch at once, without an event.
 * </ul>
 */
public final class StateTransition {

    private StateTransition() {}

    /**
     * @param current never {@code PAUSED}: the result of a check that was in flight when the monitor was paused changes
     *     nothing, and never gets here
     * @param settings for its thresholds
     */
    public static StateChange apply(
            MonitorStatus current, int failures, int successes, CheckStatus result, MonitorSettings settings) {
        if (current == MonitorStatus.PAUSED) {
            throw new IllegalArgumentException("A paused monitor has no transitions");
        }
        boolean failed = result == CheckStatus.DOWN;
        int newFailures = failed ? failures + 1 : 0;
        int newSuccesses = failed ? 0 : successes + 1;
        if (current == MonitorStatus.DOWN) {
            if (!failed && newSuccesses >= settings.recoveryThreshold()) {
                return new StateChange(status(result), newFailures, newSuccesses, Transition.RECOVERED);
            }
            return new StateChange(MonitorStatus.DOWN, newFailures, newSuccesses, Transition.NONE);
        }
        if (!failed) {
            return new StateChange(status(result), newFailures, newSuccesses, Transition.NONE);
        }
        if (newFailures >= settings.failureThreshold()) {
            return new StateChange(MonitorStatus.DOWN, newFailures, newSuccesses, Transition.WENT_DOWN);
        }
        return new StateChange(current, newFailures, newSuccesses, Transition.NONE);
    }

    private static MonitorStatus status(CheckStatus result) {
        return switch (result) {
            case UP -> MonitorStatus.UP;
            case DEGRADED -> MonitorStatus.DEGRADED;
            case DOWN -> MonitorStatus.DOWN;
        };
    }
}
