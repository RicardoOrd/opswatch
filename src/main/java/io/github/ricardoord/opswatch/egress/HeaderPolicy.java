package io.github.ricardoord.opswatch.egress;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Layer 4 of the SSRF protection: the headers a user may add to the requests to a target
 * (docs/security/ssrf-protection.md#capa-4-restricciones-de-la-petición). Checked when they are saved, and again before
 * every request (OW-024), so that a header saved before a rule existed never leaves.
 *
 * <p>The forbidden names keep request smuggling out ({@code Content-Length}, {@code Transfer-Encoding}…) and so do the
 * headers that cloud metadata services demand. The metadata addresses are blocked anyway: this is defense in depth.
 */
public final class HeaderPolicy {

    public static final int MAX_HEADERS = 10;
    public static final int MAX_NAME_LENGTH = 256;
    public static final int MAX_VALUE_BYTES = 1024;

    private static final Set<String> FORBIDDEN_NAMES = Set.of(
            "host",
            "content-length",
            "transfer-encoding",
            "connection",
            "upgrade",
            "te",
            "trailer",
            "expect",
            "cookie",
            "forwarded",
            // Google Cloud and AWS IMDSv2 metadata
            "metadata-flavor",
            "x-aws-ec2-metadata-token",
            "x-aws-ec2-metadata-token-ttl-seconds");
    private static final List<String> FORBIDDEN_PREFIXES = List.of("proxy-", "x-forwarded-");
    /** What the Oracle Cloud metadata v2 demands, in {@code Authorization}. */
    private static final String ORACLE_METADATA_AUTHORIZATION = "bearer oracle";

    /** The token grammar of RFC 9110: no spaces, no separators, no control characters. */
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    /** Printable ASCII and horizontal tab: no line break can split the header in two. */
    private static final Pattern VALUE = Pattern.compile("[\\t\\x20-\\x7E]*");

    private HeaderPolicy() {}

    /** @return the first rule broken, in order of the headers; empty if they may all leave */
    public static Optional<HeaderViolation> check(List<RequestHeader> headers) {
        if (headers.size() > MAX_HEADERS) {
            return Optional.of(
                    new HeaderViolation(-1, null, "too-many", "must have at most " + MAX_HEADERS + " headers"));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < headers.size(); i++) {
            Optional<HeaderViolation> violation = check(i, headers.get(i), seen);
            if (violation.isPresent()) {
                return violation;
            }
        }
        return Optional.empty();
    }

    private static Optional<HeaderViolation> check(int index, RequestHeader header, Set<String> seen) {
        String name = header.name();
        if (name.length() > MAX_NAME_LENGTH) {
            return name(index, "too-long", "must have at most " + MAX_NAME_LENGTH + " characters");
        }
        if (!TOKEN.matcher(name).matches()) {
            return name(index, "invalid-name", "must be an HTTP token: letters, digits and !#$%&'*+-.^_`|~");
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_NAMES.contains(lowerName) || FORBIDDEN_PREFIXES.stream().anyMatch(lowerName::startsWith)) {
            return name(index, "forbidden", "is a header OpsWatch does not send");
        }
        if (!seen.add(lowerName)) {
            return name(index, "duplicate", "is already in the list");
        }
        String value = header.value();
        if (!VALUE.matcher(value).matches()) {
            return value(index, "invalid-value", "must have only printable ASCII characters, without line breaks");
        }
        if (value.getBytes(StandardCharsets.US_ASCII).length > MAX_VALUE_BYTES) {
            return value(index, "too-long", "must have at most " + MAX_VALUE_BYTES + " bytes");
        }
        // Spaces and tabs count as one, as a lenient server would read them
        if (lowerName.equals("authorization")
                && value.strip()
                        .replaceAll("\\s+", " ")
                        .toLowerCase(Locale.ROOT)
                        .equals(ORACLE_METADATA_AUTHORIZATION)) {
            return value(index, "forbidden", "is the authorization of a cloud metadata service");
        }
        return Optional.empty();
    }

    private static Optional<HeaderViolation> name(int index, String code, String message) {
        return Optional.of(new HeaderViolation(index, "name", code, message));
    }

    private static Optional<HeaderViolation> value(int index, String code, String message) {
        return Optional.of(new HeaderViolation(index, "value", code, message));
    }
}
