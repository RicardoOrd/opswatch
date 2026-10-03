package io.github.ricardoord.opswatch.monitoring.domain;

/** A monitor and its execution state, as the API shows them together. */
public record MonitorWithState(Monitor monitor, MonitorState state) {}
