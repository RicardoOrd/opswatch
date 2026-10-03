package io.github.ricardoord.opswatch.shared.events;

import static io.github.ricardoord.opswatch.shared.events.EventPublicationRows.ARCHIVE;
import static io.github.ricardoord.opswatch.shared.events.EventPublicationRows.PENDING;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.IntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The purge deletes old archived publications and nothing else (OW-034). Above all, never a pending publication: it
 * is work not done yet. These tests are what proves that the Spring Modulith method behind the purge still only
 * touches the archive after an upgrade.
 */
@IntegrationTest
class EventPublicationPurgeJobIT {

    @Autowired
    private EventPublicationPurgeJob purgeJob;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Clock clock;

    private EventPublicationRows rows;

    @BeforeEach
    void rows() {
        rows = new EventPublicationRows(jdbc);
    }

    @AfterEach
    void deleteTheTestRows() {
        rows.deleteAll();
    }

    @Test
    void deletesTheArchivedPublicationsOlderThanTheRetentionAndKeepsTheRecentOnes() {
        Instant now = clock.instant();
        UUID old = rows.archived(now.minus(Duration.ofDays(7)).minusSeconds(60));
        UUID recent = rows.archived(now.minus(Duration.ofDays(7)).plusSeconds(60));

        purgeJob.purge();

        assertThat(rows.exists(ARCHIVE, old)).isFalse();
        assertThat(rows.exists(ARCHIVE, recent)).isTrue();
    }

    @Test
    void neverDeletesFromThePendingTableHoweverOld() {
        Instant longAgo = clock.instant().minus(Duration.ofDays(365));
        UUID pending = rows.pending(longAgo);
        UUID completedButNotArchived = rows.completedInThePendingTable(longAgo);

        purgeJob.purge();

        assertThat(rows.exists(PENDING, pending)).isTrue();
        assertThat(rows.exists(PENDING, completedButNotArchived)).isTrue();
    }
}
