package io.github.ricardoord.opswatch.monitoring.application;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestHostResolver;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorRepository;
import io.github.ricardoord.opswatch.organization.application.ProjectService;
import io.github.ricardoord.opswatch.shared.error.ConflictException;
import io.github.ricardoord.opswatch.shared.error.QuotaExceededException;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import io.github.ricardoord.opswatch.shared.lock.AdvisoryLocks;
import io.github.ricardoord.opswatch.shared.lock.LockSpace;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Monitors against simultaneous requests, with PostgreSQL: the quota of the organization and the unique name hold, no
 * monitor is left alive in a deleted project, and a {@code PATCH} never revives one that its cleanup deleted.
 */
@IntegrationTest
class MonitorConcurrencyIT {

    private static final int THREADS = 6;
    private static final int REPETITIONS = 5;
    private static final int RACES = 20;
    /** How long a blocked request is given to show it is really waiting. */
    private static final Duration STILL_WAITING = Duration.ofMillis(500);
    /** How long the asynchronous cleanup of a deleted project is given. */
    private static final Duration CLEANUP = Duration.ofSeconds(10);

    private static final String HEALTH = "https://" + TestHostResolver.PUBLIC_HOST + "/health";
    private static final SettingsChanges DEFAULTS =
            new SettingsChanges(null, null, null, null, null, PatchField.absent(), null, null, null);

    @Autowired
    private MonitorService service;

    @Autowired
    private MonitorRepository monitors;

    @Autowired
    private ProjectService projects;

    @Autowired
    private AdvisoryLocks locks;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    /** One monitor away from the limit, several creations at once, in different projects: exactly one gets in. */
    @Test
    void simultaneousCreationsDoNotGoOverTheQuotaOfTheOrganization() throws Exception {
        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            UUID owner = newUser();
            UUID organization = organizationOf(owner);
            UUID full = projectOf(organization, "Production");
            for (int i = 0; i < 49; i++) {
                insertMonitor(organization, full, "Existing " + i);
            }
            List<UUID> targets = List.of(full, projectOf(organization, "Staging"));

            List<Boolean> created = atOnce(i -> () -> {
                try {
                    service.create(owner, targets.get(i % 2), "Simultaneous " + i, HEALTH, DEFAULTS, List.of());
                    return true;
                } catch (QuotaExceededException ex) {
                    return false;
                }
            });

            assertThat(created).containsOnlyOnce(true);
            assertThat(monitors.countByOrganizationIdAndDeletedAtIsNull(organization))
                    .isEqualTo(50);
        }
    }

    /** The same name in different cases at once: one monitor, a 409 for the rest. */
    @Test
    void simultaneousCreationsWithTheSameNameCreateOne() throws Exception {
        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            UUID owner = newUser();
            UUID project = projectOf(organizationOf(owner), "Production");

            List<Boolean> created = atOnce(i -> () -> {
                try {
                    service.create(
                            owner, project, i % 2 == 0 ? "Payments API" : "PAYMENTS API", HEALTH, DEFAULTS, List.of());
                    return true;
                } catch (ConflictException ex) {
                    return false;
                }
            });

            assertThat(created).containsOnlyOnce(true);
        }
    }

    /**
     * A creation against the deletion of its project: either the creation gets a 404, or the monitor was committed
     * before the deletion, and the cleanup that runs after its commit deletes it. Never a monitor left alive in a
     * deleted project.
     */
    @Test
    void aMonitorIsNeverLeftAliveInADeletedProject() throws Exception {
        for (int race = 0; race < RACES; race++) {
            UUID owner = newUser();
            UUID project = projectOf(organizationOf(owner), "Production");

            race(
                    () -> {
                        try {
                            service.create(owner, project, "Racer", HEALTH, DEFAULTS, List.of());
                        } catch (ResourceNotFoundException ex) {
                            // The deletion got in first
                        }
                    },
                    () -> projects.delete(owner, project));

            int round = race;
            Awaitility.await()
                    .atMost(CLEANUP)
                    .untilAsserted(() -> assertThat(aliveMonitorsOf(project))
                            .as("race %d", round)
                            .isZero());
        }
    }

    /**
     * A {@code PATCH} against the cleanup of the project: it either commits before and the cleanup deletes the monitor
     * after, or it fails on the version the cleanup wrote. It never writes {@code deleted_at = NULL} back over the
     * deletion, which a bulk {@code UPDATE} without a new version would allow.
     */
    @Test
    void aPatchAgainstTheCleanupNeverRevivesTheMonitor() throws Exception {
        for (int race = 0; race < RACES; race++) {
            UUID owner = newUser();
            UUID project = projectOf(organizationOf(owner), "Production");
            UUID monitor = service.create(owner, project, "Payments API", HEALTH, DEFAULTS, List.of())
                    .monitor()
                    .id();

            race(
                    () -> {
                        try {
                            service.update(owner, monitor, "Renamed " + UUID.randomUUID(), null, DEFAULTS, null, null);
                        } catch (ResourceNotFoundException | OptimisticLockingFailureException ex) {
                            // After the deletion of the project, or on the version the cleanup wrote
                        }
                    },
                    () -> projects.delete(owner, project));

            int round = race;
            Awaitility.await()
                    .atMost(CLEANUP)
                    .untilAsserted(() -> assertThat(aliveMonitorsOf(project))
                            .as("race %d", round)
                            .isZero());
        }
    }

    /** At-least-once delivery: a second {@code ProjectDeleted} finds nothing left to delete, and changes nothing. */
    @Test
    void aRepeatedCleanupDoesNothingMore() {
        UUID owner = newUser();
        UUID project = projectOf(organizationOf(owner), "Production");
        UUID first = service.create(owner, project, "Payments API", HEALTH, DEFAULTS, List.of())
                .monitor()
                .id();
        UUID second = service.create(owner, project, "Donations API", HEALTH, DEFAULTS, List.of())
                .monitor()
                .id();
        projects.delete(owner, project);
        Awaitility.await()
                .atMost(CLEANUP)
                .untilAsserted(() -> assertThat(aliveMonitorsOf(project)).isZero());
        List<Map<String, Object>> before = rowsOf(project);

        transactions.executeWithoutResult(tx -> service.deleteAllOf(project, UUID.randomUUID()));

        assertThat(rowsOf(project)).isEqualTo(before).hasSize(2);
        assertThat(before).extracting(row -> row.get("id")).containsExactlyInAnyOrder(first, second);
    }

    /** Runs both at once, from the same start, and waits for both. */
    private static void race(Runnable one, Runnable other) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> first = executor.submit(() -> {
                await(start);
                one.run();
            });
            Future<?> second = executor.submit(() -> {
                await(start);
                other.run();
            });
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }
    }

    private List<Map<String, Object>> rowsOf(UUID project) {
        return jdbc.queryForList("""
                SELECT m.id, m.version, m.updated_at, s.updated_at AS state_updated_at
                FROM monitors m JOIN monitor_state s ON s.monitor_id = m.id
                WHERE m.project_id = ? ORDER BY m.id""", project);
    }

    /** Creations in one organization take turns on its advisory lock, whatever their project. */
    @Test
    void aCreationWaitsForTheQuotaLockOfItsOrganization() throws Exception {
        UUID owner = newUser();
        UUID organization = organizationOf(owner);
        UUID project = projectOf(organization, "Production");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> holder = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                locks.lock(LockSpace.MONITORS_OF_ORGANIZATION, organization);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> waiting =
                    executor.submit(() -> service.create(owner, project, "Waiting", HEALTH, DEFAULTS, List.of()));

            assertThatThrownBy(() -> waiting.get(STILL_WAITING.toMillis(), TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            waiting.get(10, TimeUnit.SECONDS);
        }
    }

    private List<Boolean> atOnce(IntFunction<Callable<Boolean>> task) throws InterruptedException {
        try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                Callable<Boolean> attempt = task.apply(i);
                outcomes.add(executor.submit(() -> {
                    start.await();
                    return attempt.call();
                }));
            }
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> outcome : outcomes) {
                try {
                    results.add(outcome.get());
                } catch (ExecutionException ex) {
                    throw new AssertionError("A creation failed with something unexpected", ex.getCause());
                }
            }
            return results;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Never released");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private long aliveMonitorsOf(UUID project) {
        Long alive = jdbc.queryForObject(
                "SELECT count(*) FROM monitors WHERE project_id = ? AND deleted_at IS NULL", Long.class, project);
        return alive == null ? 0 : alive;
    }

    private void insertMonitor(UUID organization, UUID project, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO monitors (id, organization_id, project_id, name, url, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'https://api.example.com/health', now(), now())""", id, organization, project, name);
        jdbc.update("""
                INSERT INTO monitor_state (monitor_id, status_changed_at, next_check_at, updated_at)
                VALUES (?, now(), now(), now())""", id);
    }

    private UUID projectOf(UUID organization, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, organization_id, name, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())""", id, organization, name);
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
