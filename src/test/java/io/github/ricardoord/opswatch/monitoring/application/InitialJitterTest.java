package io.github.ricardoord.opswatch.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongUnaryOperator;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class InitialJitterTest {

    @Test
    void drawsBelowThirtySecondsWhateverTheInterval() {
        BoundRecorder lowest = new BoundRecorder(bound -> 0);
        BoundRecorder highest = new BoundRecorder(bound -> bound - 1);

        for (int interval : List.of(30, 60, 3600)) {
            assertThat(new InitialJitter(lowest).next(interval)).isEqualTo(Duration.ZERO);
            assertThat(new InitialJitter(highest).next(interval)).isEqualTo(Duration.ofMillis(29_999));
        }
        assertThat(highest.bounds).containsOnly(30_000L);
    }

    /** min(interval, 30 s): with the shortest interval allowed being 30 s, the bound is always 30 s today. */
    @Test
    void neverWaitsAWholeIntervalOrMore() {
        BoundRecorder highest = new BoundRecorder(bound -> bound - 1);

        assertThat(new InitialJitter(highest).next(10)).isEqualTo(Duration.ofMillis(9_999));
    }

    @Test
    void theDefaultGeneratorStaysInRange() {
        InitialJitter jitter = new InitialJitter();

        for (int i = 0; i < 1000; i++) {
            assertThat(jitter.next(60)).isGreaterThanOrEqualTo(Duration.ZERO).isLessThan(InitialJitter.MAX);
        }
    }

    /** Answers {@code nextLong(bound)} as told and remembers each bound. */
    private static final class BoundRecorder implements RandomGenerator {

        private final LongUnaryOperator answer;
        private final List<Long> bounds = new ArrayList<>();

        BoundRecorder(LongUnaryOperator answer) {
            this.answer = answer;
        }

        @Override
        public long nextLong(long bound) {
            bounds.add(bound);
            return answer.applyAsLong(bound);
        }

        @Override
        public long nextLong() {
            throw new UnsupportedOperationException("Only bounded draws");
        }
    }
}
