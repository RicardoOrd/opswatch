package io.github.ricardoord.opswatch.incident.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The metrics of the incidents (docs/devops/observability.md#métricas-propias). The active ones are counted on a
 * schedule, never on a scrape, with the partial unique index; every instance reports the same database-wide figure.
 */
@Component
class IncidentMetrics {

    /** {@code opswatch_incidents_opened_total} in Prometheus. */
    static final String OPENED = "opswatch.incidents.opened";

    /** {@code opswatch_incidents_active} in Prometheus. */
    static final String ACTIVE = "opswatch.incidents.active";

    private final JdbcClient jdbc;
    private final Counter opened;
    private final AtomicLong active = new AtomicLong();

    IncidentMetrics(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.opened = Counter.builder(OPENED)
                .description("Incidents opened, once their transaction committed")
                .register(meters);
        Gauge.builder(ACTIVE, active, AtomicLong::get)
                .description("Incidents open or acknowledged")
                .register(meters);
    }

    /** Only for an incident whose transaction committed: a rolled-back check opened nothing. */
    void opened() {
        opened.increment();
    }

    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    void scheduled() {
        countActive();
    }

    /** @return what it found, also published as the gauge */
    long countActive() {
        long found = jdbc.sql("SELECT count(*) FROM incidents WHERE status <> 'RESOLVED'")
                .query(Long.class)
                .single();
        active.set(found);
        return found;
    }
}
