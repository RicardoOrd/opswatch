package io.github.ricardoord.opswatch.egress.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.egress.BlockedTargetException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Layer 2 with a resolver that answers what each test says: none depends on the real DNS. */
class GuardedDnsResolverTest {

    private final FakeHostResolver names = new FakeHostResolver()
            .with("api.example.com", "93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946")
            .with("internal.example.com", "10.0.0.5")
            .with("mixed.example.com", "93.184.216.34", "10.0.0.5")
            .with("metadata.example.com", "169.254.169.254");

    private final GuardedDnsResolver resolver = new GuardedDnsResolver(names, new IpRangeClassifier(List.of()));

    @Test
    void returnsEveryAddressOfAPublicHost() throws Exception {
        assertThat(resolver.resolve("api.example.com"))
                .containsExactly(
                        InetAddress.ofLiteral("93.184.216.34"),
                        InetAddress.ofLiteral("2606:2800:220:1:248:1893:25c8:1946"));
    }

    @Test
    void blocksAHostThatResolvesToABlockedAddress() {
        for (String host : List.of("internal.example.com", "metadata.example.com")) {
            assertThatThrownBy(() -> resolver.resolve(host))
                    .isInstanceOf(BlockedTargetException.class)
                    .hasMessageContaining(host)
                    .hasMessageNotContaining("10.0.0.5")
                    .hasMessageNotContaining("169.254");
        }
    }

    /** Case 16: one blocked address is enough, instead of picking the good one out of a mixed set. */
    @Test
    void blocksAHostWithAPublicAndAPrivateAddress() {
        assertThatThrownBy(() -> resolver.resolve("mixed.example.com")).isInstanceOf(BlockedTargetException.class);
    }

    /** Case 17: the name was public when it was saved; what counts is what it resolves to when connecting. */
    @Test
    void decidesOnWhatTheNameResolvesToNowNotWhenItWasSaved() throws Exception {
        names.with("rebind.example.com", "93.184.216.34");
        assertThat(resolver.resolve("rebind.example.com")).hasSize(1);

        names.with("rebind.example.com", "127.0.0.1");

        assertThatThrownBy(() -> resolver.resolve("rebind.example.com")).isInstanceOf(BlockedTargetException.class);
    }

    /** An address literal is classified as it is: no resolver could make it say something else. */
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.1", "169.254.169.254", "::1", "[::1]", "::ffff:127.0.0.1"})
    void classifiesAnAddressLiteralWithoutAskingTheResolver(String literal) {
        assertThatThrownBy(() -> resolver.resolve(literal)).isInstanceOf(BlockedTargetException.class);
        assertThat(names.lookups()).isEmpty();
    }

    @Test
    void anAllowedBlockOpensAPrivateAddressButNeverTheMetadata() throws Exception {
        GuardedDnsResolver permissive =
                new GuardedDnsResolver(names, new IpRangeClassifier(List.of(Cidr.parse("0.0.0.0/0"))));

        assertThat(permissive.resolve("internal.example.com")).hasSize(1);
        assertThat(permissive.resolve("127.0.0.1")).hasSize(1);
        assertThatThrownBy(() -> permissive.resolve("metadata.example.com")).isInstanceOf(BlockedTargetException.class);
    }

    @Test
    void aNameThatDoesNotResolveIsNotBlockedButUnknown() {
        assertThatThrownBy(() -> resolver.resolve("nowhere.example.com"))
                .isInstanceOf(UnknownHostException.class)
                .isNotInstanceOf(BlockedTargetException.class);
    }

    @Test
    void neverLooksTheCanonicalNameUp() {
        assertThat(resolver.resolveCanonicalHostname("api.example.com")).isEqualTo("api.example.com");
        assertThat(names.lookups()).isEmpty();
    }
}
