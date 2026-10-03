package io.github.ricardoord.opswatch.egress.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CidrTest {

    @Test
    void containsTheAddressesOfItsPrefixEvenWhenItDoesNotEndOnAByte() {
        Cidr cgnat = Cidr.parse("100.64.0.0/10");

        assertThat(cgnat.contains(InetAddress.ofLiteral("100.64.0.0"))).isTrue();
        assertThat(cgnat.contains(InetAddress.ofLiteral("100.127.255.255"))).isTrue();
        assertThat(cgnat.contains(InetAddress.ofLiteral("100.128.0.0"))).isFalse();
        assertThat(cgnat.contains(InetAddress.ofLiteral("100.63.255.255"))).isFalse();
    }

    @Test
    void neverMatchesTheOtherFamily() {
        assertThat(Cidr.parse("0.0.0.0/0").contains(InetAddress.ofLiteral("::1")))
                .isFalse();
        assertThat(Cidr.parse("::/0").contains(InetAddress.ofLiteral("127.0.0.1")))
                .isFalse();
    }

    @Test
    void acceptsBothFamiliesAndSurroundingSpaces() {
        assertThat(Cidr.parse(" 172.18.0.0/16 ")).hasToString("172.18.0.0/16");
        assertThat(Cidr.parse("fd00:ec2::/32").contains(InetAddress.ofLiteral("fd00:ec2::254")))
                .isTrue();
    }

    /** Never a name (no DNS while parsing configuration) and never an ambiguous form of an address. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "10.0.0.0",
                "10.0.0.0/",
                "10.0.0.0/33",
                "10.0.0.0/-1",
                "::/129",
                "localhost/8",
                "127.1/8",
                "010.0.0.0/8",
                "::ffff:10.0.0.0/104",
                "fe80::1%eth0/64"
            })
    void rejectsWhatIsNotAnAddressBlock(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> Cidr.parse(text));
    }
}
