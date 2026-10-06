package io.github.ricardoord.opswatch.monitoring.engine;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps {@code opswatch_monitor_checks_overdue} up to date: the monitors due for longer than
 * {@code opswatch.monitoring.engine.overdue-threshold}, the depth of the queue that the database is
 * (docs/architecture/monitoring-engine.md#11-presión-de-carga-backpressure-y-colas). Counted on a schedule, never on a
 * scrape, with the partial index of the claim.
 *
 * <p>Every instance runs it, also one without the engine, and reports the same database-wide figure: with no engine
 * running anywhere, this is what grows.
 */
@Component
@EnableConfigurationProperties(MonitoringEngineProperties.class)
class OverdueChecks {

    private final JdbcClient jdbc;
    private final MonitoringEngineProperties properties;
    private final Clock clock;
    private final AtomicLong overdue = new AtomicLong();

    OverdueChecks(JdbcClient jdbc, MonitoringEngineProperties properties, MeterRegistry meters, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        Gauge.builder(EngineMetrics.CHECKS_OVERDUE, overdue, AtomicLong::get)
                .description("Monitors due for longer than the overdue threshold")
                .register(meters);
    }

    @Scheduled(fixedDelay = 15, timeUnit = TimeUnit.SECONDS)
    void scheduled() {
        count();
    }

    /** @return what it found, also published as the gauge */
    long count() {
        Instant before = clock.instant().minus(properties.overdueThreshold());
        long found = jdbc.sql("SELECT count(*) FROM monitor_state WHERE next_check_at < :before")
                // OffsetDateTime: the JDBC 4.2 type of timestamptz, which the PostgreSQL driver maps both ways
                .param("before", before.atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
        overdue.set(found);
        return found;
    }
}
