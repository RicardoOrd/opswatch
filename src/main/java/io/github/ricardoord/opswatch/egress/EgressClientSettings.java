package io.github.ricardoord.opswatch.egress;

import java.time.Duration;
import java.util.Objects;

/**
 * What a client of {@link EgressHttpClients} may choose. Everything else (resolver, proxy, retries, redirects, cookies,
 * compression, header limits) is fixed by the SSRF protection and cannot be turned off.
 *
 * @param kind decides the schemes its requests may use
 * @param maxConnections the most connections open at once; the caller limits its own concurrency to this
 * @param timeout the longest a connection, a TLS handshake or a wait for the response may take. A request can lower the
 *     wait for its response; a deadline over the whole request is the caller's
 * @param userAgent sent with every request, so that a target can tell who calls and block it
 */
public record EgressClientSettings(TargetKind kind, int maxConnections, Duration timeout, String userAgent) {

    public EgressClientSettings {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(userAgent, "userAgent");
        if (maxConnections < 1) {
            throw new IllegalArgumentException("maxConnections must be at least 1");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }
}
