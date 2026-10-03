package io.github.ricardoord.opswatch.egress.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outbound HTTP settings (docs/devops/environments.md#egress).
 *
 * @param allowedPrivateCidrs blocks of blocked addresses opened for tests and benchmarks, as address literals in CIDR
 *     notation. Empty by default, forbidden in production (DeploymentGuardrails), and never able to open the cloud
 *     metadata (docs/security/ssrf-protection.md#4-configuración-para-pruebas-y-benchmarks)
 * @param saveResolutionTimeout how long the check on saving waits for DNS before letting the URL through
 */
@ConfigurationProperties("opswatch.egress")
public record EgressProperties(
        @DefaultValue List<String> allowedPrivateCidrs,
        @DefaultValue("2s") Duration saveResolutionTimeout) {

    public EgressProperties {
        allowedPrivateCidrs = List.copyOf(allowedPrivateCidrs);
        if (saveResolutionTimeout.isNegative() || saveResolutionTimeout.isZero()) {
            throw new IllegalArgumentException("opswatch.egress.save-resolution-timeout must be positive");
        }
        for (String block : allowedPrivateCidrs) {
            try {
                Cidr.parse(block);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("opswatch.egress.allowed-private-cidrs: " + ex.getMessage(), ex);
            }
        }
    }

    List<Cidr> allowedPrivateBlocks() {
        return allowedPrivateCidrs.stream().map(Cidr::parse).toList();
    }
}
