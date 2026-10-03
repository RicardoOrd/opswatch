package io.github.ricardoord.opswatch.egress.internal;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Decides whether OpsWatch may connect to an address: only unicast addresses routable on the internet
 * (docs/security/ssrf-protection.md#rangos-bloqueados). The single place with the blocked ranges, for the check on
 * saving and, from OW-024, for every connection. It relies on the IANA special-purpose registries, not on the
 * {@code InetAddress.isXxx()} methods, which miss CGNAT, ULA, NAT64 and the documentation ranges.
 *
 * <p>IPv6 is decided by exclusion: everything outside the global unicast block {@code 2000::/3} is blocked, which
 * covers loopback, unspecified, IPv4-compatible, discard, ULA, link-local, multicast and the local-use NAT64 prefix
 * without listing them. The IPv4 inside an IPv4-mapped, NAT64 (well-known prefix) or 6to4 address is classified as
 * IPv4.
 *
 * <p>{@code opswatch.egress.allowed-private-cidrs} opens blocks for tests and benchmarks, but never the cloud metadata
 * addresses: those stay blocked whatever it says.
 */
final class IpRangeClassifier {

    private static final List<Cidr> BLOCKED_IPV4 = cidrs(
            "0.0.0.0/8", // "this network": 0.0.0.0 reaches localhost on many systems
            "10.0.0.0/8", // private
            "100.64.0.0/10", // CGNAT, with the Alibaba Cloud metadata
            "127.0.0.0/8", // loopback
            "169.254.0.0/16", // link-local, with the metadata of AWS, GCP, Azure, Oracle and others
            "172.16.0.0/12", // private
            "192.0.0.0/24", // IETF protocol assignments
            "192.0.2.0/24", // documentation
            "192.88.99.0/24", // 6to4 relay anycast, deprecated
            "192.168.0.0/16", // private
            "198.18.0.0/15", // benchmarking
            "198.51.100.0/24", // documentation
            "203.0.113.0/24", // documentation
            "224.0.0.0/4", // multicast
            "240.0.0.0/4"); // reserved, with the broadcast address

    private static final Cidr GLOBAL_UNICAST_IPV6 = Cidr.parse("2000::/3");

    private static final List<Cidr> BLOCKED_GLOBAL_IPV6 = cidrs(
            "2001::/23", // IETF protocol assignments, with Teredo (2001::/32)
            "2001:db8::/32", // documentation
            "3fff::/20"); // documentation (RFC 9637)

    private static final Cidr NAT64_WELL_KNOWN = Cidr.parse("64:ff9b::/96");
    private static final Cidr SIX_TO_FOUR = Cidr.parse("2002::/16");

    /** Never allowed, not even by allowed-private-cidrs. */
    private static final List<Cidr> CLOUD_METADATA = cidrs(
            "169.254.0.0/16", // AWS, GCP, Azure, Oracle, ECS credentials
            "100.100.100.200/32", // Alibaba Cloud
            "fd00:ec2::/32"); // AWS over IPv6

    private final List<Cidr> allowedPrivate;

    /** @param allowedPrivate blocks opened for tests and benchmarks; never in production (DeploymentGuardrails) */
    IpRangeClassifier(List<Cidr> allowedPrivate) {
        this.allowedPrivate = List.copyOf(allowedPrivate);
    }

    boolean isAllowed(InetAddress address) {
        byte[] bytes = address.getAddress();
        byte[] embedded = embeddedIpv4(bytes);
        if (isCloudMetadata(bytes) || (embedded != null && isCloudMetadata(embedded))) {
            return false;
        }
        if (isExplicitlyAllowed(bytes) || (embedded != null && isExplicitlyAllowed(embedded))) {
            return true;
        }
        if (embedded != null) {
            return isPublicIpv4(embedded);
        }
        return bytes.length == 4 ? isPublicIpv4(bytes) : isPublicIpv6(bytes);
    }

    private static boolean isPublicIpv4(byte[] address) {
        return BLOCKED_IPV4.stream().noneMatch(block -> block.contains(address));
    }

    private static boolean isPublicIpv6(byte[] address) {
        return GLOBAL_UNICAST_IPV6.contains(address)
                && BLOCKED_GLOBAL_IPV6.stream().noneMatch(block -> block.contains(address));
    }

    private static boolean isCloudMetadata(byte[] address) {
        return CLOUD_METADATA.stream().anyMatch(block -> block.contains(address));
    }

    private boolean isExplicitlyAllowed(byte[] address) {
        return allowedPrivate.stream().anyMatch(block -> block.contains(address));
    }

    /**
     * The IPv4 that an IPv6 address carries, if any. Java already turns {@code ::ffff:a.b.c.d} into an
     * {@link Inet4Address}, but a resolver could hand over the sixteen bytes.
     */
    private static byte @Nullable [] embeddedIpv4(byte[] address) {
        if (address.length != 16) {
            return null;
        }
        if (isIpv4Mapped(address) || NAT64_WELL_KNOWN.contains(address)) {
            return Arrays.copyOfRange(address, 12, 16);
        }
        if (SIX_TO_FOUR.contains(address)) {
            return Arrays.copyOfRange(address, 2, 6);
        }
        return null;
    }

    private static boolean isIpv4Mapped(byte[] address) {
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return address[10] == (byte) 0xFF && address[11] == (byte) 0xFF;
    }

    private static List<Cidr> cidrs(String... blocks) {
        return Arrays.stream(blocks).map(Cidr::parse).toList();
    }
}
