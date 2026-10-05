package io.github.ricardoord.opswatch.monitoring.application;

import io.github.ricardoord.opswatch.shared.error.InvalidParameterException;
import java.time.Duration;

/**
 * How far back the statistics of a monitor look. No longer than the retention of the raw checks (30 days,
 * docs/database/data-retention.md): a window of 90 days would quietly sum up 30.
 */
public enum StatsWindow {
    LAST_24_HOURS("24h", Duration.ofHours(24)),
    LAST_7_DAYS("7d", Duration.ofDays(7)),
    LAST_30_DAYS("30d", Duration.ofDays(30));

    private final String label;
    private final Duration length;

    StatsWindow(String label, Duration length) {
        this.label = label;
        this.length = length;
    }

    /** @throws InvalidParameterException for anything but {@code 24h}, {@code 7d} or {@code 30d} (400) */
    public static StatsWindow of(String label) {
        for (StatsWindow window : values()) {
            if (window.label.equals(label)) {
                return window;
            }
        }
        throw new InvalidParameterException("'window' must be 24h, 7d or 30d.");
    }

    /** As the API names it. */
    public String label() {
        return label;
    }

    public Duration length() {
        return length;
    }
}
