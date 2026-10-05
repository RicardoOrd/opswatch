package io.github.ricardoord.opswatch.monitoring.engine;

import io.github.ricardoord.opswatch.TestHostResolver;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Monitors scheduled in 2001, a past that only the tests of the engine use. The tests share the database, and every
 * other test schedules its monitors at the real time: a claim at a time in 2001 sees only the monitors made here.
 *
 * <p>{@link #clear()} unschedules them all, before and after each test, so that no test sees those of another, nor
 * those of a run that stopped halfway (the container may be reused between runs).
 */
final class PastSchedule {

    static final Instant EPOCH = Instant.parse("2001-01-01T00:00:00Z");

    /** Every monitor made here is due before this, and nothing else is. */
    private static final Instant HORIZON = Instant.parse("2002-01-01T00:00:00Z");

    static final String URL = "https://" + TestHostResolver.PUBLIC_HOST + "/health";

    private final JdbcTemplate jdbc;
    private @Nullable UUID organization;
    private @Nullable UUID project;

    PastSchedule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void clear() {
        jdbc.update("UPDATE monitor_state SET next_check_at = NULL WHERE next_check_at < ?", at(HORIZON));
    }

    /** With an interval of 60 s and a timeout of 10 s. */
    UUID monitorDueAt(Instant nextCheckAt) {
        return monitorDueAt(nextCheckAt, 60, 10_000);
    }

    UUID monitorDueAt(Instant nextCheckAt, int intervalSeconds, int timeoutMs) {
        return monitorsDueAt(1, nextCheckAt, intervalSeconds, timeoutMs).getFirst();
    }

    List<UUID> monitorsDueAt(int count, Instant nextCheckAt, int intervalSeconds, int timeoutMs) {
        UUID projectId = project();
        List<UUID> ids = new ArrayList<>();
        List<Object[]> monitors = new ArrayList<>();
        List<Object[]> states = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            monitors.add(new Object[] {id, organization, projectId, "Monitor " + id, URL, intervalSeconds, timeoutMs});
            // Its status since well before the checks of the test: none of them is older than the status
            states.add(new Object[] {id, at(EPOCH.minusSeconds(86_400)), at(nextCheckAt)});
        }
        jdbc.batchUpdate("""
                INSERT INTO monitors (id, organization_id, project_id, name, url, interval_seconds, timeout_ms,
                                      created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())""", monitors);
        jdbc.batchUpdate("""
                INSERT INTO monitor_state (monitor_id, status_changed_at, next_check_at, updated_at)
                VALUES (?, ?, ?, now())""", states);
        return ids;
    }

    @Nullable
    Instant nextCheckAt(UUID monitorId) {
        OffsetDateTime next = jdbc.queryForObject(
                "SELECT next_check_at FROM monitor_state WHERE monitor_id = ?", OffsetDateTime.class, monitorId);
        return next == null ? null : next.toInstant();
    }

    long checksOf(UUID monitorId) {
        Long checks =
                jdbc.queryForObject("SELECT count(*) FROM monitor_checks WHERE monitor_id = ?", Long.class, monitorId);
        return checks == null ? 0 : checks;
    }

    /** OffsetDateTime: the JDBC 4.2 type of timestamptz, which the PostgreSQL driver maps both ways */
    static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private UUID project() {
        if (project == null) {
            organization = UUID.randomUUID();
            project = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO organizations (id, name, created_at, updated_at) VALUES (?, 'CharityLink', now(), now())",
                    organization);
            jdbc.update("""
                    INSERT INTO projects (id, organization_id, name, created_at, updated_at)
                    VALUES (?, ?, 'Production', now(), now())""", project, organization);
        }
        return project;
    }
}
