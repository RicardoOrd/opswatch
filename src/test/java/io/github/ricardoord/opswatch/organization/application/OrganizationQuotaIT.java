package io.github.ricardoord.opswatch.organization.application;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.organization.Role;
import io.github.ricardoord.opswatch.organization.domain.OrganizationRepository;
import io.github.ricardoord.opswatch.shared.error.QuotaExceededException;
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

/** The quota of organizations per owner against simultaneous creations, with PostgreSQL. */
@IntegrationTest
class OrganizationQuotaIT {

    private static final int THREADS = 6;
    private static final int REPETITIONS = 5;

    @Autowired
    private OrganizationService service;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * An owner one organization away from the limit creates several at once: exactly one gets in. Without the lock
     * per user, all of them would count four and go through.
     */
    @Test
    void simultaneousCreationsDoNotGoOverTheQuota() throws Exception {
        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            UUID owner = newUser();
            for (int i = 0; i < 4; i++) {
                service.create(owner, "Existing " + i);
            }

            List<Boolean> created = createAtOnce(owner);

            assertThat(created).containsOnlyOnce(true);
            assertThat(organizations.countActiveWithRole(owner, Role.OWNER)).isEqualTo(5);
        }
    }

    private List<Boolean> createAtOnce(UUID owner) throws InterruptedException {
        try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                String name = "Simultaneous " + i;
                outcomes.add(executor.submit(() -> {
                    start.await();
                    try {
                        service.create(owner, name);
                        return true;
                    } catch (QuotaExceededException ex) {
                        return false;
                    }
                }));
            }
            start.countDown();
            List<Boolean> created = new ArrayList<>();
            for (Future<Boolean> outcome : outcomes) {
                try {
                    created.add(outcome.get());
                } catch (ExecutionException ex) {
                    throw new AssertionError("A creation failed with something other than the quota", ex.getCause());
                }
            }
            return created;
        }
    }

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, 'Ana', '{bcrypt}not-a-real-hash', now(), now())""", id, uniqueEmail());
        return id;
    }
}
