/**
 * Controlled outbound HTTP: target validation, DNS resolution with IP pinning and hardened clients. Every request
 * to a user-supplied URL goes through this module (SSRF protection).
 */
@ApplicationModule(
        displayName = "Egress",
        allowedDependencies = {"shared"})
@NullMarked
package io.github.ricardoord.opswatch.egress;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
