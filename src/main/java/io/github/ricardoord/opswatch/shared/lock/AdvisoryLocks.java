package io.github.ricardoord.opswatch.shared.lock;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction-scoped advisory locks of PostgreSQL, to serialize a check and the write that depends on it when there
 * is no row to lock, such as a quota: counting and inserting under the lock does not overcount with simultaneous
 * requests.
 */
@Component
public class AdvisoryLocks {

    private final JdbcTemplate jdbc;

    public AdvisoryLocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Waits for the other transactions that hold the lock of {@code key} in {@code space}, and holds it until the
     * current transaction ends. Only inside the caller's transaction: a lock taken in a transaction of its own would end
     * on return.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(LockSpace space, UUID key) {
        jdbc.queryForObject(
                "SELECT 1 FROM pg_advisory_xact_lock(CAST(? AS integer), hashtext(CAST(? AS text)))",
                Integer.class,
                space.number(),
                key.toString());
    }
}
