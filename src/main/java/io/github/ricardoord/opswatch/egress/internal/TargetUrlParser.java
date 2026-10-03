package io.github.ricardoord.opswatch.egress.internal;

import io.github.ricardoord.opswatch.egress.TargetKind;
import io.github.ricardoord.opswatch.shared.error.TargetNotAllowedException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The syntactic half of layer 1 (docs/security/ssrf-protection.md#capa-1-validación-al-guardar): everything decided
 * without DNS. Rejects anything it is not sure about, and returns the URL normalized together with its host.
 */
final class TargetUrlParser {

    static final int MAX_LENGTH = 2048;

    /** What a host name reached through a registry-based authority looks like: host, then an optional port. */
    private static final Pattern REGISTRY_AUTHORITY = Pattern.compile("([^:\\[\\]]*)(?::([0-9]*))?");

    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final int MAX_HOST_LENGTH = 253;
    private static final List<String> FORBIDDEN_SUFFIXES = List.of(".localhost", ".local", ".internal", ".home.arpa");

    /** The detail of each rejection: which rule failed, never what the host resolved to. */
    enum Rejection {
        INVALID("The URL is not valid."),
        TOO_LONG("The URL is too long: " + MAX_LENGTH + " characters at most."),
        SCHEME("Only http and https URLs are allowed."),
        HTTPS_ONLY("Only https URLs are allowed."),
        CREDENTIALS("The URL must not contain credentials: send them in a header."),
        PORT("The port is not allowed: use 80, 443 or one from 1024 to 65535."),
        HOST("The host is not allowed.");

        private final String detail;

        Rejection(String detail) {
            this.detail = detail;
        }

        TargetNotAllowedException exception() {
            return new TargetNotAllowedException(detail);
        }
    }

    /**
     * @param uri the URL to store: scheme and host in lower case, host in punycode, no fragment
     * @param host as in the URL, IPv6 literals in brackets
     * @param literal the address, if the host is one; null if it is a name to resolve
     */
    record ParsedTarget(URI uri, String host, @Nullable InetAddress literal) {}

    private TargetUrlParser() {}

    /** @throws TargetNotAllowedException if a rule rejects it (422) */
    static ParsedTarget parse(String url, TargetKind kind) {
        if (url.length() > MAX_LENGTH) {
            throw Rejection.TOO_LONG.exception();
        }
        URI uri = uri(url).orElseThrow(Rejection.INVALID::exception);
        if (uri.getScheme() == null) {
            throw Rejection.INVALID.exception();
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!kind.allows(scheme)) {
            throw (kind == TargetKind.WEBHOOK ? Rejection.HTTPS_ONLY : Rejection.SCHEME).exception();
        }
        if (uri.isOpaque()) {
            throw Rejection.INVALID.exception();
        }
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty()) {
            throw Rejection.INVALID.exception();
        }
        if (uri.getRawUserInfo() != null || authority.indexOf('@') >= 0) {
            throw Rejection.CREDENTIALS.exception();
        }

        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null) {
            // Not a server name for java.net.URI: a non-ASCII (IDN) name, or something that is no name at all
            Matcher registry = REGISTRY_AUTHORITY.matcher(authority);
            if (!registry.matches()) {
                throw Rejection.HOST.exception();
            }
            host = registry.group(1);
            port = port(registry.group(2));
        }
        if (port != -1 && port != 80 && port != 443 && (port < 1024 || port > 65535)) {
            throw Rejection.PORT.exception();
        }

        InetAddress literal;
        String normalizedHost;
        if (host.startsWith("[") && host.endsWith("]")) {
            String inner = host.substring(1, host.length() - 1);
            literal = IpLiterals.parse(inner).orElseThrow(Rejection.HOST::exception);
            normalizedHost = "[" + inner.toLowerCase(Locale.ROOT) + "]";
        } else {
            normalizedHost = asciiHost(host);
            literal = numericHost(normalizedHost);
            if (literal == null) {
                checkHostName(normalizedHost);
            }
        }

        StringBuilder normalized = new StringBuilder(scheme).append("://").append(normalizedHost);
        if (port != -1) {
            normalized.append(':').append(port);
        }
        if (uri.getRawPath() != null) {
            normalized.append(uri.getRawPath());
        }
        if (uri.getRawQuery() != null) {
            normalized.append('?').append(uri.getRawQuery());
        }
        // Punycode can make it longer than what was sent
        if (normalized.length() > MAX_LENGTH) {
            throw Rejection.TOO_LONG.exception();
        }
        URI result = uri(normalized.toString()).orElseThrow(Rejection.INVALID::exception);
        return new ParsedTarget(result, normalizedHost, literal);
    }

    private static Optional<URI> uri(String text) {
        try {
            return Optional.of(new URI(text));
        } catch (URISyntaxException ex) {
            return Optional.empty();
        }
    }

    private static int port(@Nullable String digits) {
        if (digits == null || digits.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            throw Rejection.PORT.exception();
        }
    }

    /** IDN to punycode under the STD3 rules (no {@code _}, {@code %} or spaces), lower case, without the root dot. */
    private static String asciiHost(String host) {
        String ascii;
        try {
            ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            throw Rejection.HOST.exception();
        }
        // "localhost." is localhost: the root dot must not dodge the rules below
        if (ascii.endsWith(".")) {
            ascii = ascii.substring(0, ascii.length() - 1);
        }
        if (ascii.isEmpty()) {
            throw Rejection.HOST.exception();
        }
        return ascii;
    }

    /**
     * No top-level domain is all digits, so a host whose last label is all digits is an address: it must be one in
     * dotted decimal with four octets. {@code 2130706433}, {@code 0177.0.0.1} or {@code 127.1} are rejected, because
     * resolvers read them as addresses each in its own way.
     */
    private static @Nullable InetAddress numericHost(String host) {
        String lastLabel = host.substring(host.lastIndexOf('.') + 1);
        if (lastLabel.isEmpty() || !lastLabel.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        return IpLiterals.parse(host)
                .filter(address -> IpLiterals.isCanonicalIpv4(host))
                .orElseThrow(Rejection.HOST::exception);
    }

    /** RFC 1123 grammar, at least two labels, and none of the names that only mean something inside a network. */
    private static void checkHostName(String host) {
        if (host.length() > MAX_HOST_LENGTH || host.indexOf('.') < 0) {
            throw Rejection.HOST.exception();
        }
        for (String label : host.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                throw Rejection.HOST.exception();
            }
        }
        if (FORBIDDEN_SUFFIXES.stream().anyMatch(suffix -> host.endsWith(suffix) || host.equals(suffix.substring(1)))) {
            throw Rejection.HOST.exception();
        }
    }
}
