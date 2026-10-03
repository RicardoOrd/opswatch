package io.github.ricardoord.opswatch.monitoring.domain;

/** How many monitors are in one status. */
public record StatusCount(MonitorStatus status, long count) {}
