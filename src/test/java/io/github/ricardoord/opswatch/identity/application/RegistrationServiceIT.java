package io.github.ricardoord.opswatch.identity.application;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.shared.error.ConflictException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class RegistrationServiceIT {

    @Autowired
    private RegistrationService registration;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Both requests can pass the existence check before either inserts. The unique index then rejects the second
     * INSERT, and that must still be a conflict, never a 500.
     */
    @RepeatedTest(20)
    void simultaneousRegistrationsWithTheSameEmailCreateOneAccount() throws Exception {
        String email = uniqueEmail();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> outcomes = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String variant : List.of(email, email.toUpperCase(Locale.ROOT))) {
                outcomes.add(executor.submit(() -> {
                    start.await();
                    try {
                        registration.register(variant, "Ana", "correct horse battery");
                        return Outcome.CREATED;
                    } catch (ConflictException ex) {
                        return Outcome.CONFLICT;
                    }
                }));
            }
            start.countDown();
        }

        assertThat(results(outcomes)).containsExactlyInAnyOrder(Outcome.CREATED, Outcome.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email))
                .isOne();
    }

    private static List<Outcome> results(List<Future<Outcome>> outcomes)
            throws InterruptedException, ExecutionException {
        List<Outcome> results = new ArrayList<>();
        for (Future<Outcome> outcome : outcomes) {
            results.add(outcome.get());
        }
        return results;
    }

    private enum Outcome {
        CREATED,
        CONFLICT
    }
}
