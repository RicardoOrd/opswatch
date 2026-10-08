package io.github.ricardoord.opswatch.shared.ratelimit;

import java.time.Duration;
import org.springframework.boot.convert.DurationStyle;

/**
 * A number of requests per period, written {@code 10/1m} in the properties. The bucket refills gradually: after a
 * burst that empties it, one more request is allowed every {@code period / capacity}.
 */
public record RateLimit(int capacity, Duration period) {

    public RateLimit {
        if (capacity < 1 || period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("A rate limit needs a positive capacity and period");
        }
    }

    /**
     * Parses {@code <capacity>/<period>}, the period in Spring Boot's simple format ({@code 30s}, {@code 15m},
     * {@code 1h}). The configuration properties binder finds it by name.
     */
    public static RateLimit valueOf(String value) {
        String message = "A rate limit is a positive <capacity>/<period>, like 10/1m, not '" + value + "'";
        int slash = value.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException(message);
        }
        try {
            return new RateLimit(
                    Integer.parseInt(value.substring(0, slash).strip()),
                    DurationStyle.SIMPLE.parse(value.substring(slash + 1).strip()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(message, ex);
        }
    }

    @Override
    public String toString() {
        return capacity + "/" + period;
    }
}
