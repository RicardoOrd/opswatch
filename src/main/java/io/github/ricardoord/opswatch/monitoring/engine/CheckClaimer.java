package io.github.ricardoord.opswatch.monitoring.engine;

import io.github.ricardoord.opswatch.monitoring.application.CheckResultRecorder;
import io.github.ricardoord.opswatch.monitoring.application.MonitorHeaders;
import io.github.ricardoord.opswatch.monitoring.domain.Monitor;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorRepository;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSettings;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Takes the monitors that are due and schedules their next check, in one short transaction
 * (docs/architecture/monitoring-engine.md#algoritmo-de-programación). The database is the queue: any number of instances
 * claim at once, and none ever gets a monitor another one has.
 */
@Component
class CheckClaimer {

    /**
     * {@code FOR UPDATE OF s SKIP LOCKED}: the row of the state that a pause, a resume, a deletion or a {@code PATCH}
     * holds is skipped, never waited for, and so is the one another instance is claiming. Only that row: the monitor is
     * read, not locked, because every writer of its settings locks the state first. The next check counts from when this
     * one was due, so the schedule does not drift; one late by more than its interval runs once and its next check is an
     * interval from now, without catching up.
     */
    static final String CLAIM = """
            WITH due AS (
                SELECT s.monitor_id, s.next_check_at AS scheduled_for, m.interval_seconds
                FROM monitor_state s
                JOIN monitors m ON m.id = s.monitor_id
                WHERE s.next_check_at <= :now
                    AND m.deleted_at IS NULL
                ORDER BY s.next_check_at
                LIMIT :max
                FOR UPDATE OF s SKIP LOCKED
            )
            UPDATE monitor_state s
            SET next_check_at = CASE
                    WHEN due.scheduled_for + make_interval(secs => due.interval_seconds) > :now
                        THEN due.scheduled_for + make_interval(secs => due.interval_seconds)
                    ELSE :now + make_interval(secs => due.interval_seconds)
                END,
                updated_at = :now
            FROM due
            WHERE s.monitor_id = due.monitor_id
            RETURNING s.monitor_id, due.scheduled_for""";

    private final JdbcClient jdbc;
    private final MonitorRepository monitors;
    private final MonitorHeaders headers;
    private final CheckResultRecorder recorder;
    private final TransactionOperations transactions;
    private final MeterRegistry meters;
    private final Clock clock;

    CheckClaimer(
            JdbcClient jdbc,
            MonitorRepository monitors,
            MonitorHeaders headers,
            CheckResultRecorder recorder,
            TransactionOperations transactions,
            MeterRegistry meters,
            Clock clock) {
        this.jdbc = jdbc;
        this.monitors = monitors;
        this.headers = headers;
        this.recorder = recorder;
        this.transactions = transactions;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * A monitor whose request cannot be built (its headers do not decrypt) is claimed all the same, so that it waits for
     * its next interval, and counted as an error of OpsWatch: it never holds back the others.
     *
     * @param max at most this many, the most overdue first
     * @return ready to run, with no transaction open
     */
    List<ClaimedCheck> claim(int max) {
        return claim(max, clock.instant());
    }

    /** @param now when they are claimed; the tests set it */
    List<ClaimedCheck> claim(int max, Instant now) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be at least 1");
        }
        List<ClaimedCheck> claimed = new ArrayList<>();
        Map<UUID, RuntimeException> unusable = new LinkedHashMap<>();
        Timer.Sample claiming = Timer.start(meters);
        try {
            transactions.executeWithoutResult(
                    transaction -> claimLocked(max, now.truncatedTo(ChronoUnit.MICROS), claimed, unusable));
        } finally {
            claiming.stop(meters.timer(EngineMetrics.CLAIM_DURATION));
        }
        // Only once the claim is committed: a claim rolled back took nothing
        unusable.forEach(recorder::recordError);
        return claimed;
    }

    private void claimLocked(int max, Instant now, List<ClaimedCheck> claimed, Map<UUID, RuntimeException> unusable) {
        List<Due> due = jdbc.sql(CLAIM)
                // OffsetDateTime: the JDBC 4.2 type of timestamptz, which the PostgreSQL driver maps both ways
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("max", max)
                .query((row, number) -> new Due(
                        row.getObject("monitor_id", UUID.class),
                        row.getObject("scheduled_for", OffsetDateTime.class).toInstant()))
                .list();
        if (due.isEmpty()) {
            return;
        }
        // Read after the claim, in its transaction: what it runs is what was saved when it was claimed
        Map<UUID, Monitor> byId = monitors
                .findAllById(due.stream().map(Due::monitorId).toList())
                .stream()
                .collect(Collectors.toMap(Monitor::id, Function.identity()));
        due.stream().sorted(Comparator.comparing(Due::scheduledFor)).forEach(check -> {
            Monitor monitor = byId.get(check.monitorId());
            try {
                claimed.add(new ClaimedCheck(MonitorSnapshot.of(monitor), request(monitor), check.scheduledFor()));
            } catch (RuntimeException ex) {
                unusable.put(check.monitorId(), ex);
            }
        });
    }

    /** The headers are decrypted here and live only in memory, until the check ends. */
    private ProbeRequest request(Monitor monitor) {
        MonitorSettings settings = monitor.settings();
        return new ProbeRequest(
                monitor.url(),
                settings.httpMethod(),
                headers.unseal(monitor),
                Duration.ofMillis(settings.timeoutMs()),
                settings.followRedirects());
    }

    private record Due(UUID monitorId, Instant scheduledFor) {}
}
