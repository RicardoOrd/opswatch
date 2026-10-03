package io.github.ricardoord.opswatch.monitoring.web;

import io.github.ricardoord.opswatch.monitoring.domain.MonitorStatus;
import java.util.Map;

/**
 * The monitors of a project by status, deleted ones aside.
 *
 * @param byStatus every status, also those with none
 */
public record MonitorSummaryResponse(long total, Map<MonitorStatus, Long> byStatus) {

    static MonitorSummaryResponse from(Map<MonitorStatus, Long> byStatus) {
        return new MonitorSummaryResponse(
                byStatus.values().stream().mapToLong(Long::longValue).sum(), byStatus);
    }
}
