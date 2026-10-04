package io.github.ricardoord.opswatch.monitoring.application;

import java.time.Duration;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * How long a new or resumed monitor waits for its first check: random in {@code [0, min(interval, 30 s))}
 * (docs/architecture/monitoring-engine.md#algoritmo-de-programación). Without it, a thousand monitors created at once by
 * a script would run at once forever, since each next check is scheduled from the previous one.
 */
@Component
public class InitialJitter {

    static final Duration MAX = Duration.ofSeconds(30);

    private final RandomGenerator random;

    /** @param random the bean of {@code ClockConfiguration}, which the tests replace with a fixed one */
    InitialJitter(RandomGenerator random) {
        this.random = random;
    }

    public Duration next(int intervalSeconds) {
        long boundMillis = Math.min(Duration.ofSeconds(intervalSeconds).toMillis(), MAX.toMillis());
        return Duration.ofMillis(random.nextLong(boundMillis));
    }
}
