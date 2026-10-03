package io.github.ricardoord.opswatch.egress.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The first and the last address of every blocked range, and a public neighbour on each side that must pass
 * (docs/security/ssrf-protection.md#rangos-bloqueados).
 */
class IpRangeClassifierTest {

    private final IpRangeClassifier classifier = new IpRangeClassifier(List.of());

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0.0.0.0",
                "0.255.255.255", // this network
                "10.0.0.0",
                "10.255.255.255", // private
                "100.64.0.0",
                "100.127.255.255", // CGNAT
                "127.0.0.0",
                "127.0.0.1",
                "127.255.255.255", // loopback
                "169.254.0.0",
                "169.254.169.254",
                "169.254.255.255", // link-local and metadata
                "172.16.0.0",
                "172.31.255.255", // private
                "192.0.0.0",
                "192.0.0.255", // IETF protocol assignments
                "192.0.2.0",
                "192.0.2.255", // documentation
                "192.88.99.0",
                "192.88.99.255", // 6to4 relay
                "192.168.0.0",
                "192.168.255.255", // private
                "198.18.0.0",
                "198.19.255.255", // benchmarking
                "198.51.100.0",
                "198.51.100.255", // documentation
                "203.0.113.0",
                "203.0.113.255", // documentation
                "224.0.0.0",
                "239.255.255.255", // multicast
                "240.0.0.0",
                "255.255.255.255" // reserved and broadcast
            })
    void blocksEveryNonPublicIpv4Range(String address) {
        assertThat(classifier.isAllowed(InetAddress.ofLiteral(address))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "1.0.0.0",
                "8.8.8.8",
                "9.255.255.255",
                "11.0.0.0",
                "93.184.216.34",
                "100.63.255.255",
                "100.128.0.0",
                "126.255.255.255",
                "128.0.0.0",
                "169.253.255.255",
                "169.255.0.0",
                "172.15.255.255",
                "172.32.0.0",
                "191.255.255.255",
                "192.0.1.0",
                "192.0.3.0",
                "192.88.98.255",
                "192.88.100.0",
                "192.167.255.255",
                "192.169.0.0",
                "198.17.255.255",
                "198.20.0.0",
                "198.51.99.255",
                "198.51.101.0",
                "203.0.112.255",
                "203.0.114.0",
                "223.255.255.255"
            })
    void letsPublicIpv4Through(String address) {
        assertThat(classifier.isAllowed(InetAddress.ofLiteral(address))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "::",
                "::1", // unspecified and loopback
                "::7f00:1", // IPv4-compatible, deprecated
                "64:ff9b::7f00:1",
                "64:ff9b::a00:1", // NAT64 of loopback and of a private address
                "64:ff9b:1::1", // local-use NAT64: blocked whole
                "100::",
                "100::ffff:ffff:ffff:ffff", // discard
                "2001::",
                "2001:1ff:ffff:ffff:ffff:ffff:ffff:ffff", // IETF protocol assignments, Teredo
                "2001:db8::",
                "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff", // documentation
                "2002:7f00:1::",
                "2002:a00:1::", // 6to4 of loopback and of a private address
                "3fff::",
                "3fff:fff:ffff:ffff:ffff:ffff:ffff:ffff", // documentation (RFC 9637)
                "fc00::",
                "fd00:ec2::254",
                "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", // unique local, AWS metadata
                "fe80::",
                "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff", // link-local
                "ff00::",
                "ff02::1",
                "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", // multicast
                "1fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
                "4000::" // outside the global unicast block
            })
    void blocksEveryIpv6AddressThatIsNotGlobalUnicast(String address) {
        assertThat(classifier.isAllowed(InetAddress.ofLiteral(address))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2000::",
                "2001:200::",
                "2001:4860:4860::8888",
                "2001:db7:ffff:ffff:ffff:ffff:ffff:ffff",
                "2001:db9::",
                "2606:4700:4700::1111",
                "3ffe:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
                "3fff:1000::",
                "2002:808:808::", // 6to4 of 8.8.8.8
                "64:ff9b::808:808" // NAT64 of 8.8.8.8
            })
    void letsPublicIpv6Through(String address) {
        assertThat(classifier.isAllowed(InetAddress.ofLiteral(address))).isTrue();
    }

    @Test
    void classifiesTheIpv4InsideAnIpv4MappedAddressGivenAsSixteenBytes() throws Exception {
        assertThat(classifier.isAllowed(mapped(127, 0, 0, 1))).isFalse();
        assertThat(classifier.isAllowed(mapped(169, 254, 169, 254))).isFalse();
        assertThat(classifier.isAllowed(mapped(8, 8, 8, 8))).isTrue();
    }

    @Test
    void allowedPrivateBlocksOpenOnlyWhatTheyName() {
        IpRangeClassifier withPrivate = new IpRangeClassifier(List.of(Cidr.parse("10.0.0.0/8"), Cidr.parse("::1/128")));

        assertThat(withPrivate.isAllowed(InetAddress.ofLiteral("10.1.2.3"))).isTrue();
        assertThat(withPrivate.isAllowed(InetAddress.ofLiteral("::1"))).isTrue();
        assertThat(withPrivate.isAllowed(InetAddress.ofLiteral("192.168.1.1"))).isFalse();
        assertThat(withPrivate.isAllowed(InetAddress.ofLiteral("127.0.0.1"))).isFalse();
    }

    /** Case 25 of the SSRF table: not even opening everything opens the cloud metadata. */
    @Test
    void neverOpensTheCloudMetadataWhateverIsAllowed() throws Exception {
        IpRangeClassifier everything = new IpRangeClassifier(List.of(Cidr.parse("0.0.0.0/0"), Cidr.parse("::/0")));

        assertThat(everything.isAllowed(InetAddress.ofLiteral("127.0.0.1"))).isTrue();
        assertThat(everything.isAllowed(InetAddress.ofLiteral("100.100.100.201")))
                .isTrue();
        for (String metadata :
                List.of("169.254.169.254", "169.254.170.2", "100.100.100.200", "fd00:ec2::254", "64:ff9b::a9fe:a9fe")) {
            assertThat(everything.isAllowed(InetAddress.ofLiteral(metadata)))
                    .as(metadata)
                    .isFalse();
        }
        assertThat(everything.isAllowed(mapped(169, 254, 169, 254))).isFalse();
    }

    /** {@code ::ffff:a.b.c.d} with its sixteen bytes, as a resolver could return it. */
    private static InetAddress mapped(int a, int b, int c, int d) throws Exception {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xFF;
        bytes[11] = (byte) 0xFF;
        bytes[12] = (byte) a;
        bytes[13] = (byte) b;
        bytes[14] = (byte) c;
        bytes[15] = (byte) d;
        return Inet6Address.getByAddress(null, bytes, -1);
    }
}
