package io.github.ricardoord.opswatch.monitoring.application;

import io.github.ricardoord.opswatch.monitoring.domain.CheckStats;
import io.github.ricardoord.opswatch.monitoring.domain.CheckStatus;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorCheckRepository;
import io.github.ricardoord.opswatch.monitoring.domain.RecordedCheck;
import io.github.ricardoord.opswatch.organization.Permission;
import io.github.ricardoord.opswatch.shared.error.InvalidParameterException;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import io.github.ricardoord.opswatch.shared.web.CursorPage;
import io.github.ricardoord.opswatch.shared.web.CursorQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The history of the checks of a monitor and its statistics, for anyone who may read the monitor. The query always
 * filters by the monitor it authorized: neither a cursor nor a window can reach the checks of another one.
 */
@Service
public class MonitorStatsQueries {

    private final MonitorAccess monitorAccess;
    private final MonitorCheckRepository checks;
    private final Clock clock;

    MonitorStatsQueries(MonitorAccess monitorAccess, MonitorCheckRepository checks, Clock clock) {
        this.monitorAccess = monitorAccess;
        this.checks = checks;
        this.clock = clock;
    }

    /**
     * Newest first.
     *
     * @param statuses only these; empty for every status
     * @param from inclusive; null for no lower bound
     * @param to exclusive; null for no upper bound
     * @throws ResourceNotFoundException if the monitor is missing or deleted, or the user may not read it (404)
     * @throws InvalidParameterException if {@code from} is not before {@code to} (400)
     */
    @Transactional(readOnly = true)
    public CursorPage<RecordedCheck> checksOf(
            UUID userId,
            UUID monitorId,
            Set<CheckStatus> statuses,
            @Nullable Instant from,
            @Nullable Instant to,
            CursorQuery page) {
        monitorAccess.require(userId, monitorId, Permission.MONITOR_READ);
        if (from != null && to != null && !from.isBefore(to)) {
            throw new InvalidParameterException("'from' must be before 'to'.");
        }
        return CursorPage.of(
                checks.findPage(monitorId, statuses, from, to, page.after(), page.rowsToFetch()),
                page.limit(),
                RecordedCheck::checkedAt);
    }

    /**
     * The window ends now. Repeatable read: its two queries see the same checks.
     *
     * @throws ResourceNotFoundException if the monitor is missing or deleted, or the user may not read it (404)
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WindowStats statsOf(UUID userId, UUID monitorId, StatsWindow window) {
        monitorAccess.require(userId, monitorId, Permission.MONITOR_READ);
        // PostgreSQL keeps microseconds: the bounds shown are the ones the query used
        Instant to = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant from = to.minus(window.length());
        return new WindowStats(window, from, to, checks.statsOf(monitorId, from, to));
    }

    /**
     * @param from inclusive
     * @param to exclusive
     */
    public record WindowStats(StatsWindow window, Instant from, Instant to, CheckStats stats) {}
}
