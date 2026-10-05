package io.github.ricardoord.opswatch.monitoring.engine;

import static io.github.ricardoord.opswatch.monitoring.engine.PastSchedule.EPOCH;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.IntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Several instances claiming at once against the same PostgreSQL (docs/testing/testing-strategy.md#concurrencia): each
 * claimer is a thread with its own transactions, as each instance would be. A duplicate would run a check twice
 * against a third party, and could open a false incident; an omission would leave a monitor unchecked.
 */
@IntegrationTest
class CheckClaimerConcurrencyIT {

    private static final int MONITORS = 1_000;
    private static final int CLAIMERS = 4;
    private static final int ROUNDS = 20;
    private static final int INTERVAL_SECONDS = 60;
    /** Small, so that each round takes many claims and the claimers keep running into each other. */
    private static final int BATCH = 25;

    @Autowired
    private CheckClaimer claimer;

    @Autowired
    private JdbcTemplate jdbc;

    private PastSchedule past;

    @BeforeEach
    void clearThePast() {
        past = new PastSchedule(jdbc);
        past.clear();
    }

    @AfterEach
    void unschedule() {
        past.clear();
    }

    @Test
    void everyDueMonitorIsClaimedExactlyOncePerRound() throws Exception {
        Set<UUID> monitors = new HashSet<>(past.monitorsDueAt(MONITORS, EPOCH, INTERVAL_SECONDS, 10_000));
        ExecutorService threads = Executors.newFixedThreadPool(CLAIMERS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                // Every monitor became due one interval after the previous round
                Instant now = EPOCH.plusSeconds((long) round * INTERVAL_SECONDS + 1);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<List<UUID>>> claimers = new ArrayList<>();
                for (int i = 0; i < CLAIMERS; i++) {
                    claimers.add(threads.submit(claimUntilNothingIsLeft(now, go)));
                }
                go.countDown();
                List<UUID> claimed = new ArrayList<>();
                for (Future<List<UUID>> each : claimers) {
                    claimed.addAll(each.get(60, TimeUnit.SECONDS));
                }

                List<UUID> ours = claimed.stream().filter(monitors::contains).toList();
                assertThat(ours).as("round %d: claimed exactly once", round).doesNotHaveDuplicates();
                assertThat(new HashSet<>(ours))
                        .as("round %d: none left out", round)
                        .isEqualTo(monitors);
                assertThat(dueAt(now)).as("round %d: none still due", round).isZero();
            }
        } finally {
            threads.shutdownNow();
        }
    }

    /** Until a claim comes back empty: what other claimers hold, they will claim. */
    private Callable<List<UUID>> claimUntilNothingIsLeft(Instant now, CountDownLatch go) {
        return () -> {
            go.await();
            List<UUID> claimed = new ArrayList<>();
            List<ClaimedCheck> batch;
            do {
                batch = claimer.claim(BATCH, now);
                batch.forEach(check -> claimed.add(check.monitor().monitorId()));
            } while (!batch.isEmpty());
            return claimed;
        };
    }

    /** Only the monitors of this test are scheduled in the past. */
    private long dueAt(Instant now) {
        Long due = jdbc.queryForObject(
                "SELECT count(*) FROM monitor_state WHERE next_check_at <= ?", Long.class, PastSchedule.at(now));
        return due == null ? 0 : due;
    }
}
