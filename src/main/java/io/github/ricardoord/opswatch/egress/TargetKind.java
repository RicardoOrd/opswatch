package io.github.ricardoord.opswatch.egress;

import java.util.Set;

/** What a target URL is for, which decides the schemes it may use (docs/security/ssrf-protection.md). */
public enum TargetKind {
    MONITOR(Set.of("http", "https")),
    /** Only {@code https}: the body carries the incident and the signature. */
    WEBHOOK(Set.of("https"));

    private final Set<String> schemes;

    TargetKind(Set<String> schemes) {
        this.schemes = schemes;
    }

    /** @param scheme in lower case */
    public boolean allows(String scheme) {
        return schemes.contains(scheme);
    }
}
