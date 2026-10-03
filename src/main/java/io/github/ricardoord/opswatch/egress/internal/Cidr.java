package io.github.ricardoord.opswatch.egress.internal;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/** A block of addresses in CIDR notation ({@code 10.0.0.0/8}, {@code fc00::/7}), of one family. */
record Cidr(byte[] network, int prefixLength) {

    Cidr {
        network = network.clone();
        if (prefixLength < 0 || prefixLength > network.length * 8) {
            throw new IllegalArgumentException("Prefix length out of range: " + prefixLength);
        }
    }

    /**
     * Address literals only, never names: parsing must not touch DNS. IPv4 in strict dotted decimal.
     *
     * @throws IllegalArgumentException if the text is not a CIDR block
     */
    static Cidr parse(String text) {
        String trimmed = text.strip();
        int slash = trimmed.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("Not a CIDR block (address/prefix): " + text);
        }
        String address = trimmed.substring(0, slash);
        InetAddress parsed = IpLiterals.parse(address)
                .orElseThrow(() -> new IllegalArgumentException("Not an address literal: " + address));
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(trimmed.substring(slash + 1));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Not a prefix length: " + text, ex);
        }
        // "::ffff:10.0.0.0/104" reads as IPv4: Java turns mapped addresses into Inet4Address
        if (parsed instanceof Inet4Address && address.contains(":")) {
            throw new IllegalArgumentException("Write IPv4 blocks in IPv4 notation: " + text);
        }
        return new Cidr(parsed.getAddress(), prefixLength);
    }

    boolean contains(InetAddress address) {
        return contains(address.getAddress());
    }

    boolean contains(byte[] address) {
        if (address.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (address[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits) & 0xFF;
        return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    @Override
    public byte[] network() {
        return network.clone();
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof Cidr cidr && prefixLength == cidr.prefixLength && Arrays.equals(network, cidr.network);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(network) + prefixLength;
    }

    @Override
    public String toString() {
        try {
            return InetAddress.getByAddress(network).getHostAddress() + "/" + prefixLength;
        } catch (UnknownHostException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
