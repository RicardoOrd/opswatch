package io.github.ricardoord.opswatch.organization.application;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.organization.Role;
import io.github.ricardoord.opswatch.shared.error.BusinessRuleViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The invariant of the last {@code OWNER} against simultaneous changes, with PostgreSQL (T-16). */
@IntegrationTest
class MembershipRaceIT {

    private static final int REPETITIONS = 50;

    @Autowired
    private OrganizationService organizations;

    @Autowired
    private MembershipService memberships;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Two {@code OWNER}s demote each other at once: one change goes through and the other gets 409, so an
     * {@code OWNER} always remains. Without the lock on the organization, both would see another {@code OWNER} left
     * and both would go through.
     */
    @Test
    void twoOwnersDemotingEachOtherAtOnceLeaveOneOwner() throws Exception {
        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            UUID ana = newUser();
            UUID bea = newUser();
            UUID organization =
                    organizations.create(ana, "CharityLink").organization().id();
            memberships.add(ana, organization, email(bea), Role.OWNER);

            List<String> outcomes = atOnce(
                    () -> memberships.changeRole(ana, organization, bea, Role.ADMIN, null),
                    () -> memberships.changeRole(bea, organization, ana, Role.ADMIN, null));

            assertThat(outcomes).containsExactlyInAnyOrder("changed", "last-owner");
            assertThat(owners(organization)).isEqualTo(1);
        }
    }

    private List<String> atOnce(Runnable first, Runnable second) throws InterruptedException {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (Runnable change : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        change.run();
                        return "changed";
                    } catch (BusinessRuleViolationException ex) {
                        return "last-owner";
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (ExecutionException ex) {
                    throw new AssertionError("A change failed with something other than the invariant", ex.getCause());
                }
            }
            return outcomes;
        }
    }

    private long owners(UUID organization) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                Long.class,
                organization);
        return count == null ? 0 : count;
    }

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, 'Ana', '{bcrypt}not-a-real-hash', now(), now())""", id, uniqueEmail());
        return id;
    }

    private String email(UUID user) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user);
    }
}
