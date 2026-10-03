package io.github.ricardoord.opswatch.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.shared.events.IncompleteEventPublicationsMonitor.Pending;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The gauge of pending publications and the warning about those pending for too long (OW-034). The database is shared
 * with other tests, so the assertions compare against what was there before.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class IncompleteEventPublicationsMonitorIT {

    @Autowired
    private IncompleteEventPublicationsMonitor monitor;

    @Autowired
    private MeterRegistry meters;

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
    void countsThePendingPublicationsAndWarnsAboutThoseOverdue(CapturedOutput output) {
        Instant now = clock.instant();
        Pending before = monitor.check();
        rows.pending(now.minus(Duration.ofMinutes(1)));
        rows.pending(now.minus(Duration.ofHours(1)));
        rows.archived(now.minus(Duration.ofHours(1)));

        Pending after = monitor.check();

        assertThat(after.total() - before.total()).isEqualTo(2);
        assertThat(after.overdue() - before.overdue()).isEqualTo(1);
        assertThat(after.oldest()).isNotNull();
        assertThat(meters.get(IncompleteEventPublicationsMonitor.GAUGE).gauge().value())
                .isEqualTo(after.total());
        assertThat(output).contains("event publications pending for more than PT15M");
    }
}
