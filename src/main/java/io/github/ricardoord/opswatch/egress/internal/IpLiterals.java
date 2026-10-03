package io.github.ricardoord.opswatch.egress.internal;

import java.net.InetAddress;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Address literals in the only forms OpsWatch accepts: IPv4 in dotted decimal with four octets and no leading zeros,
 * and IPv6 without a zone. {@link InetAddress#ofLiteral} alone is not enough: it also takes {@code 127.1} and
 * {@code 01.2.3.4}, forms that resolvers read differently and that serve to sneak past filters.
 */
final class IpLiterals {

    private static final String OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])";
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");

    private IpLiterals() {}

    /** Never touches DNS. Empty if the text is not an address in an accepted form. */
    static Optional<InetAddress> parse(String text) {
        if (IPV4.matcher(text).matches()) {
            return Optional.of(InetAddress.ofLiteral(text));
        }
        if (text.indexOf(':') < 0 || text.indexOf('%') >= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.ofLiteral(text));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    static boolean isCanonicalIpv4(String text) {
        return IPV4.matcher(text).matches();
    }
}
