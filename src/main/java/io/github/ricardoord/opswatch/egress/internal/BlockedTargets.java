package io.github.ricardoord.opswatch.egress.internal;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code opswatch_egress_blocked_total{reason}}: the requests the SSRF protection stopped while they were leaving
 * (docs/security/ssrf-protection.md). Growing out of the ordinary means someone is probing the internal network through
 * OpsWatch. The reason is one of a closed set, never the host nor the address.
 */
final class BlockedTargets {

    static final String METRIC = "opswatch.egress.blocked";

    enum Reason {
        /** A name that resolves to an address that is not public, or such an address as a literal (layer 2). */
        ADDRESS,
        /** A URL that breaks a rule of layer 1 that needs no DNS: scheme, host, port, credentials. */
        URL,
        /** A header that breaks {@code HeaderPolicy} (layer 4). */
        HEADER
    }

    private final MeterRegistry meters;

    BlockedTargets(MeterRegistry meters) {
        this.meters = meters;
    }

    void count(Reason reason) {
        meters.counter(METRIC, "reason", reason.name()).increment();
    }
}
