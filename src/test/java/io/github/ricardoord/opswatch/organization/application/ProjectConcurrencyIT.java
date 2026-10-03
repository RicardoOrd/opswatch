package io.github.ricardoord.opswatch.organization.application;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.organization.ProjectDirectory;
import io.github.ricardoord.opswatch.organization.domain.OrganizationRepository;
import io.github.ricardoord.opswatch.organization.domain.ProjectRepository;
import io.github.ricardoord.opswatch.shared.error.ConflictException;
import io.github.ricardoord.opswatch.shared.error.QuotaExceededException;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Projects against simultaneous requests, with PostgreSQL: the quota and the unique name hold, and the two locks that
 * keep a deletion from leaving something alive inside what it deletes do wait for each other.
 */
@IntegrationTest
class ProjectConcurrencyIT {

    private static final int THREADS = 6;
    private static final int REPETITIONS = 5;
    /** How long a blocked request is given to show it is really waiting. */
    private static final Duration STILL_WAITING = Duration.ofMillis(500);

    @Autowired
    private ProjectService service;

    @Autowired
    private ProjectDirectory directory;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    /** One project away from the limit, several creations at once: exactly one gets in. */
    @Test
    void simultaneousCreationsDoNotGoOverTheQuota() throws Exception {
        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            UUID owner = newUser();
            UUID organization = organizationOf(owner);
            for (int i = 0; i < 19; i++) {
                service.create(owner, organization, "Existing " + i, null);
            }

            List<Boolean> created = atOnce(i -> () -> {
                try {
                    service.create(owner, organization, "Simultaneous " + i, null);
                    return true;
                } catch (QuotaExceededException ex) {
                    return false;
                }
            });

            assertThat(created).containsOnlyOnce(true);
            assertThat(projects.countByOrganizationIdAndDeletedAtIsNull(organization))
                    .isEqualTo(20);
        }
    }

    /** The same name in different cases at once: one project, a 409 for the rest. */
    @Test
    void simultaneousCreationsWithTheSameNameCreateOne() throws Exception {
        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            UUID owner = newUser();
            UUID organization = organizationOf(owner);

            List<Boolean> created = atOnce(i -> () -> {
                try {
                    service.create(owner, organization, i % 2 == 0 ? "Production" : "PRODUCTION", null);
                    return true;
                } catch (ConflictException ex) {
                    return false;
                }
            });

            assertThat(created).containsOnlyOnce(true);
        }
    }

    /** {@link ProjectDirectory#lockActive}: what is added to a project exists before its deletion goes through. */
    @Test
    void aDeletionWaitsForTheTransactionThatLockedTheProject() throws Exception {
        UUID owner = newUser();
        UUID project =
                service.create(owner, organizationOf(owner), "Production", null).id();

        assertThatTheSecondWaitsForTheFirst(() -> directory.lockActive(project), () -> service.delete(owner, project));

        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> directory.lockActive(project)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void lockingAProjectNeedsATransaction() {
        UUID owner = newUser();
        UUID project =
                service.create(owner, organizationOf(owner), "Production", null).id();

        assertThatThrownBy(() -> directory.lockActive(project)).isInstanceOf(IllegalTransactionStateException.class);
    }

    /** A creation takes the organization's row, as its deletion does: no project outlives its organization. */
    @Test
    void aCreationWaitsForTheTransactionThatLockedItsOrganization() throws Exception {
        UUID owner = newUser();
        UUID organization = organizationOf(owner);

        assertThatTheSecondWaitsForTheFirst(
                () -> organizations.findActiveByIdForUpdate(organization),
                () -> service.create(owner, organization, "Production", null));
    }

    /** Authorization comes before the lock: someone who is not a member never waits for it, nor makes others wait. */
    @Test
    void anOutsiderIsTurnedAwayWithoutTakingTheOrganizationLock() throws Exception {
        UUID owner = newUser();
        UUID organization = organizationOf(owner);
        UUID outsider = newUser();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> holder = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                organizations.findActiveByIdForUpdate(organization);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> attempt = executor.submit(() -> service.create(outsider, organization, "Planted", null));

            assertThatThrownBy(() -> attempt.get(STILL_WAITING.toMillis() * 4, TimeUnit.MILLISECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ResourceNotFoundException.class);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Runs {@code first} in a transaction that stays open, then {@code second} in another thread: the second must
     * still be waiting after {@link #STILL_WAITING}, and finish once the first commits.
     */
    private void assertThatTheSecondWaitsForTheFirst(Runnable first, Runnable second) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> holder = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                first.run();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> waiting = executor.submit(second);

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
