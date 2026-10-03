package io.github.ricardoord.opswatch.shared.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.IntegrationTest;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** Advisory locks against PostgreSQL: who waits for whom, and for how long. */
@IntegrationTest
class AdvisoryLocksIT {

    /** How long a blocked transaction is given to show it is really waiting. */
    private static final Duration STILL_WAITING = Duration.ofMillis(500);

    @Autowired
    private AdvisoryLocks locks;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void theSameKeyInTheSameSpaceWaitsUntilTheHolderCommits() throws Exception {
        UUID key = UUID.randomUUID();

        try (Holder holder = new Holder(LockSpace.MONITORS_OF_ORGANIZATION, key)) {
            Future<?> waiting =
                    holder.executor.submit(() -> lockInTransaction(LockSpace.MONITORS_OF_ORGANIZATION, key));

            assertThatThrownBy(() -> waiting.get(STILL_WAITING.toMillis(), TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            holder.release();
            waiting.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void anotherKeyOrAnotherSpaceDoesNotWait() throws Exception {
        UUID key = UUID.randomUUID();

        try (Holder holder = new Holder(LockSpace.MONITORS_OF_ORGANIZATION, key)) {
            holder.executor
                    .submit(() -> lockInTransaction(LockSpace.ORGANIZATIONS_OWNED_BY_USER, key))
                    .get(STILL_WAITING.toMillis() * 4, TimeUnit.MILLISECONDS);
            holder.executor
                    .submit(() -> lockInTransaction(LockSpace.MONITORS_OF_ORGANIZATION, UUID.randomUUID()))
                    .get(STILL_WAITING.toMillis() * 4, TimeUnit.MILLISECONDS);
        }
    }

    /** Outside a transaction the lock would end at once, so it is refused. */
    @Test
    void needsATransaction() {
        assertThatThrownBy(() -> locks.lock(LockSpace.MONITORS_OF_ORGANIZATION, UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void everySpaceHasANumberOfItsOwn() {
        assertThat(LockSpace.values()).extracting(LockSpace::number).doesNotHaveDuplicates();
    }

    private void lockInTransaction(LockSpace space, UUID key) {
        transactions.executeWithoutResult(tx -> locks.lock(space, key));
    }

    /** A transaction in another thread that holds a lock until released. */
    private final class Holder implements AutoCloseable {

        private final ExecutorService executor = Executors.newFixedThreadPool(2);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Future<?> holding;

        Holder(LockSpace space, UUID key) throws InterruptedException {
            CountDownLatch locked = new CountDownLatch(1);
            holding = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                locks.lock(space, key);
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        }

        /** Commits the holder's transaction and waits for it. */
        void release() throws Exception {
            release.countDown();
            holding.get(10, TimeUnit.SECONDS);
        }

        /** Waits for every task, the holder's included, which ends once released. */
        @Override
        public void close() {
            release.countDown();
            executor.close();
        }
    }
}
