package io.github.ricardoord.opswatch.egress;

import io.github.ricardoord.opswatch.shared.error.TargetNotAllowedException;
import java.net.URI;

/**
 * Layer 1 of the SSRF protection: the check of a target URL when it is saved
 * (docs/security/ssrf-protection.md#capa-1-validación-al-guardar). It gives the user immediate feedback, but it is
 * <strong>not</strong> the final barrier: DNS can change after saving, so every connection checks again (OW-024).
 *
 * <p>It resolves the host name, which is external I/O: never call it inside a transaction.
 */
public interface TargetPolicy {

    /**
     * @return the URL to store and show: scheme and host in lower case, host in punycode, no fragment
     * @throws TargetNotAllowedException if a rule rejects it (422). The detail names the rule, never an address the
     *     host resolved to
     */
    URI validate(String url, TargetKind kind);
}
