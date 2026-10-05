package io.github.ricardoord.opswatch.monitoring.engine;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static io.github.ricardoord.opswatch.monitoring.engine.PastSchedule.EPOCH;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.egress.RequestHeader;
import io.github.ricardoord.opswatch.monitoring.application.MonitorService;
import io.github.ricardoord.opswatch.monitoring.application.SettingsChanges;
import io.github.ricardoord.opswatch.monitoring.domain.Monitor;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSnapshot;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStateRepository;
import io.github.ricardoord.opswatch.monitoring.domain.ProbeMethod;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The claim against PostgreSQL (docs/architecture/monitoring-engine.md#algoritmo-de-programación): what it takes, how
 * it schedules the next check, what it skips, and the plan of its query. Claims happen at times of 2001
 * ({@link PastSchedule}), so they see only the monitors of these tests.
 */
@IntegrationTest
class CheckClaimerIT {

    private static final Duration MINUTE = Duration.ofSeconds(60);

    @Autowired
    private CheckClaimer claimer;

    @Autowired
    private MonitorService service;

    @Autowired
    private MonitorStateRepository states;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private Clock clock;

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

    /** Fixed rate: the next check counts from when this one was due, not from when it was claimed. */
    @Test
    void claimsWhatIsDueAndSchedulesItsNextCheckAnIntervalAfterItWasDue() {
        UUID due = past.monitorDueAt(EPOCH);
        UUID notYet = past.monitorDueAt(EPOCH.plusSeconds(10));
        Instant now = EPOCH.plusSeconds(5);

        List<ClaimedCheck> claimed = claimer.claim(10, now);

        assertThat(claimed).extracting(check -> check.monitor().monitorId()).containsExactly(due);
        assertThat(claimed.getFirst().scheduledFor()).isEqualTo(EPOCH);
        assertThat(past.nextCheckAt(due)).isEqualTo(EPOCH.plus(MINUTE));
        assertThat(past.nextCheckAt(notYet)).isEqualTo(EPOCH.plusSeconds(10));
    }

    @Test
    void aMonitorLateByMoreThanItsIntervalRunsOnceAndSkipsToAnIntervalFromNow() {
        UUID late = past.monitorDueAt(EPOCH);
        Instant now = EPOCH.plusSeconds(150);

        assertThat(claimer.claim(10, now))
                .extracting(check -> check.monitor().monitorId())
                .containsExactly(late);
        assertThat(past.nextCheckAt(late)).isEqualTo(now.plus(MINUTE));
        assertThat(claimer.claim(10, now)).isEmpty();
    }

    /** Late by exactly its interval: the next one would be now, and is an interval from now instead. */
    @Test
    void aMonitorLateByExactlyItsIntervalSkipsToAnIntervalFromNow() {
        UUID late = past.monitorDueAt(EPOCH);
        Instant now = EPOCH.plus(MINUTE);

        assertThat(claimer.claim(10, now)).hasSize(1);
        assertThat(past.nextCheckAt(late)).isEqualTo(now.plus(MINUTE));
    }

    @Test
    void claimsAtMostWhatItIsAskedForTheMostOverdueFirst() {
        UUID first = past.monitorDueAt(EPOCH);
        UUID second = past.monitorDueAt(EPOCH.plusSeconds(1));
        UUID third = past.monitorDueAt(EPOCH.plusSeconds(2));
        Instant now = EPOCH.plusSeconds(10);

        assertThat(claimer.claim(2, now))
                .extracting(check -> check.monitor().monitorId())
                .containsExactly(first, second);
        assertThat(past.nextCheckAt(third)).isEqualTo(EPOCH.plusSeconds(2));
        assertThat(claimer.claim(2, now))
                .extracting(check -> check.monitor().monitorId())
                .containsExactly(third);
    }

    /** What the check needs, read in the claim: the settings, the URL and the headers in clear. */
    @Test
    void buildsTheRequestOfTheCheckWithTheHeadersDecrypted() {
        UUID owner = newUser();
        List<RequestHeader> headers = List.of(new RequestHeader("Authorization", "Bearer s3cr3t-t0ken"));
        SettingsChanges changes =
                new SettingsChanges(ProbeMethod.HEAD, null, null, 120, 5_000, PatchField.absent(), false, null, null);
        Monitor monitor = service.create(
                        owner, projectOf(organizationOf(owner)), "Payments API", PastSchedule.URL, changes, headers)
                .monitor();
        reschedule(monitor.id(), EPOCH);

        List<ClaimedCheck> claimed = claimer.claim(10, EPOCH.plusSeconds(1));

        assertThat(claimed).hasSize(1);
        ClaimedCheck check = claimed.getFirst();
        assertThat(check.monitor()).isEqualTo(MonitorSnapshot.of(monitor));
        assertThat(check.request().url()).isEqualTo(URI.create(PastSchedule.URL));
        assertThat(check.request().method()).isEqualTo(ProbeMethod.HEAD);
        assertThat(check.request().timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(check.request().followRedirects()).isFalse();
        assertThat(check.request().headers()).isEqualTo(headers);
        assertThat(check.toString()).doesNotContain("s3cr3t-t0ken");
        assertThat(past.nextCheckAt(monitor.id())).isEqualTo(EPOCH.plusSeconds(120));
    }

    /**
     * Headers that do not decrypt are an error of OpsWatch for that monitor alone: it is claimed, so it waits for its
     * next interval, and the others of the batch run.
     */
    @Test
    void aMonitorWhoseHeadersDoNotDecryptIsAnErrorAndDoesNotHoldBackTheOthers() {
        UUID broken = past.monitorDueAt(EPOCH);
        UUID healthy = past.monitorDueAt(EPOCH.plusSeconds(1));
        jdbc.update(
                "UPDATE monitors SET request_headers = ? WHERE id = ?", new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, broken);
        double errors = errors();

        List<ClaimedCheck> claimed = claimer.claim(10, EPOCH.plusSeconds(5));

        assertThat(claimed).extracting(check -> check.monitor().monitorId()).containsExactly(healthy);
        assertThat(past.nextCheckAt(broken)).isEqualTo(EPOCH.plus(MINUTE));
        assertThat(errors()).isEqualTo(errors + 1);
    }

    /**
     * The row a pause holds is skipped, not waited for; once the pause commits, the monitor is no longer scheduled.
     * Resuming, deleting and editing lock the same row first.
     */
    @Test
    void skipsAMonitorBeingPausedAndThenFindsItUnscheduled() throws Exception {
        UUID paused = past.monitorDueAt(EPOCH);
        UUID other = past.monitorDueAt(EPOCH);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try {
            Future<?> pausing = threads.submit(() -> transactions.executeWithoutResult(transaction -> {
                states.findByIdForUpdate(paused).orElseThrow().pause(clock);
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(claimer.claim(10, EPOCH.plusSeconds(1)))
                    .extracting(check -> check.monitor().monitorId())
                    .containsExactly(other);
            // Returned while the pause still holds the row: it did not wait for it
            assertThat(pausing).isNotDone();

            release.countDown();
            pausing.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            threads.shutdownNow();
        }

        assertThat(past.nextCheckAt(paused)).isNull();
        assertThat(claimer.claim(10, EPOCH.plus(Duration.ofHours(1))))
                .extracting(check -> check.monitor().monitorId())
                .containsExactly(other);
    }

    /** A deleted monitor is never scheduled; if its state still were, the claim would not take it either. */
    @Test
    void neverClaimsADeletedMonitor() {
        UUID deleted = past.monitorDueAt(EPOCH);
        jdbc.update("UPDATE monitors SET deleted_at = now() WHERE id = ?", deleted);

        assertThat(claimer.claim(10, EPOCH.plusSeconds(1))).isEmpty();
        assertThat(past.nextCheckAt(deleted)).isEqualTo(EPOCH);
    }

    /** The partial index of the monitors due, with statistics on a table where few of them are. */
    @Test
    void theClaimUsesTheIndexOfTheMonitorsDue() {
        past.monitorsDueAt(5, EPOCH, 60, 10_000);
        past.monitorsDueAt(2_000, EPOCH.plus(Duration.ofDays(30)), 60, 10_000);
        jdbc.execute("ANALYZE monitor_state");

        List<String> plan = jdbcClient
                .sql("EXPLAIN " + CheckClaimer.CLAIM)
                .param("now", PastSchedule.at(EPOCH.plusSeconds(1)))
                .param("max", 200)
                .query(String.class)
                .list();

        assertThat(String.join("\n", plan)).contains("ix_monitor_state_due");
    }

    private void reschedule(UUID monitorId, Instant nextCheckAt) {
        jdbc.update(
                "UPDATE monitor_state SET next_check_at = ? WHERE monitor_id = ?",
                PastSchedule.at(nextCheckAt),
                monitorId);
    }

    private double errors() {
        return meters.counter("opswatch.monitor.checks", "outcome", "ERROR", "reason", "NONE")
                .count();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private UUID projectOf(UUID organization) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, organization_id, name, created_at, updated_at)
                VALUES (?, ?, 'Production', now(), now())""", id, organization);
        return id;
    }

    private UUID organizationOf(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, name, created_at, updated_at) VALUES (?, 'CharityLink', now(), now())",
                id);
        jdbc.update("""
                INSERT INTO memberships (organization_id, user_id, role, created_at, updated_at)
                VALUES (?, ?, 'OWNER', now(), now())""", id, owner);
        return id;
    }

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, 'Ana', '{bcrypt}not-a-real-hash', now(), now())""", id, uniqueEmail());
        return id;
    }
}
