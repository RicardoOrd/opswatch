package io.github.ricardoord.opswatch.monitoring.application;

import io.github.ricardoord.opswatch.egress.RequestHeader;
import io.github.ricardoord.opswatch.monitoring.domain.Monitor;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorState;
import java.util.List;

/**
 * A monitor as the API shows it: with its state and the names of its headers, never their values. Nobody reads a value
 * back, whatever their role (docs/security/authorization-model.md).
 */
public record MonitorView(Monitor monitor, MonitorState state, List<HeaderName> headers) {

    public MonitorView {
        headers = List.copyOf(headers);
    }

    static MonitorView of(Monitor monitor, MonitorState state, List<RequestHeader> headers) {
        return new MonitorView(
                monitor,
                state,
                headers.stream()
                        .map(header ->
                                new HeaderName(header.name(), !header.value().isEmpty()))
                        .toList());
    }

    /** @param hasValue whether a value is stored, which is never shown */
    public record HeaderName(String name, boolean hasValue) {}
}
