package io.github.ricardoord.opswatch.monitoring;

/**
 * Why a check was {@code DOWN} (docs/architecture/domain-model.md#monitorcheck). Public because it travels in
 * {@code MonitorWentDown}. The names are stored as they are in {@code monitor_checks.failure_reason} and
 * {@code monitor_state.last_failure_reason}: renaming one needs a migration.
 *
 * <p>An internal error of OpsWatch is never one of these: blaming the target for it would open a false incident.
 */
public enum FailureReason {
    /** No complete response headers before the timeout of the monitor. */
    TIMEOUT,
    /** The name did not resolve. */
    DNS_FAILURE,
    /** Refused, unreachable or reset. */
    CONNECTION_FAILED,
    /** Failed handshake, or a certificate that is not trusted, has expired or is not for the host. */
    TLS_FAILURE,
    /** A response, with a code outside the expected range. */
    UNEXPECTED_STATUS,
    /** More redirects than allowed, or a loop. */
    TOO_MANY_REDIRECTS,
    /** The target, or a redirect, was stopped by the SSRF protection. */
    TARGET_BLOCKED,
    /** A malformed response, headers over the limit, or a redirect without a valid {@code Location}. */
    PROTOCOL_ERROR
}
