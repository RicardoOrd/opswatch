package io.github.ricardoord.opswatch.monitoring.engine.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.core5.concurrent.Cancellable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DeadlineTest {

    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stopTheTimers() {
        timers.shutdownNow();
    }

    @Test
    void cancelsTheRequestInFlightWhenItPasses() {
        CountingRequest first = new CountingRequest();
        CountingRequest inFlight = new CountingRequest();

        try (Deadline deadline = Deadline.start(timers, Duration.ofMillis(50))) {
            deadline.track(first);
            deadline.track(inFlight);

            await().atMost(Duration.ofSeconds(2)).until(deadline::expired);
            assertThat(inFlight.cancels()).isEqualTo(1);
            assertThat(first.cancels()).isZero();
        }
    }

    /** A redirect hop that starts after the deadline never leaves. */
    @Test
    void cancelsARequestTrackedAfterItPassed() {
        try (Deadline deadline = Deadline.start(timers, Duration.ZERO)) {
            await().atMost(Duration.ofSeconds(2)).until(deadline::expired);
            CountingRequest late = new CountingRequest();

            deadline.track(late);

            assertThat(late.cancels()).isEqualTo(1);
        }
    }

    @Test
    void aClosedDeadlineNeverPasses() {
        CountingRequest done = new CountingRequest();
        Deadline deadline = Deadline.start(timers, Duration.ofMillis(100));
        deadline.track(done);

        deadline.close();

        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(1)).until(() -> !deadline.expired());
        assertThat(done.cancels()).isZero();
    }

    private static final class CountingRequest implements Cancellable {

        private final AtomicInteger cancels = new AtomicInteger();

        @Override
        public boolean cancel() {
            cancels.incrementAndGet();
            return true;
        }

        int cancels() {
            return cancels.get();
        }
    }
}
