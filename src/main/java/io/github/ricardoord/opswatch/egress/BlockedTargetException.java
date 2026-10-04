package io.github.ricardoord.opswatch.egress;

import java.net.UnknownHostException;

/**
 * A request that the SSRF protection stops before it leaves: the host resolves to a blocked address, or the URL or a
 * header breaks a rule (docs/security/ssrf-protection.md). The monitoring engine records it as {@code TARGET_BLOCKED}.
 *
 * <p>It extends {@link UnknownHostException} so that it crosses the API of the HTTP client as it is, without wrappers.
 * The message names the host and the rule, never an address the host resolved to.
 */
public class BlockedTargetException extends UnknownHostException {

    private final String rule;

    public BlockedTargetException(String host, String rule) {
        super("Target blocked by the egress policy: " + host + ". " + rule);
        this.rule = rule;
    }

    /** Which rule stopped it, in words fit for the user: never an address. */
    public String rule() {
        return rule;
    }
}
