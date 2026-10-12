package io.github.ricardoord.opswatch.egress.internal;

import io.github.ricardoord.opswatch.egress.EgressHttpClients;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;

/**
 * The real {@link EgressHttpClients}, for tests of other modules that need it without a Spring context: the
 * implementation is internal to {@code egress}.
 */
public final class TestEgressHttpClients {

    private TestEgressHttpClients() {}

    /**
     * @param names what each name resolves to
     * @param allowedPrivateCidrs as {@code opswatch.egress.allowed-private-cidrs}: {@code 127.0.0.0/8} reaches a
     *     simulated target on the loopback
     */
    public static EgressHttpClients resolvingWith(FakeHostResolver names, String... allowedPrivateCidrs) {
        return new DefaultEgressHttpClients(
                names,
                new EgressProperties(List.of(allowedPrivateCidrs), Duration.ofSeconds(2)),
                new SimpleMeterRegistry());
    }

    /**
     * As {@link #resolvingWith}, trusting a certificate of the tests instead of the JVM: an {@code https} target on the
     * loopback, as a webhook must be.
     */
    public static EgressHttpClients trusting(
            TestCertificate certificate, FakeHostResolver names, String... allowedPrivateCidrs) {
        return new DefaultEgressHttpClients(
                names,
                new EgressProperties(List.of(allowedPrivateCidrs), Duration.ofSeconds(2)),
                new SimpleMeterRegistry(),
                certificate.trust());
    }
}
