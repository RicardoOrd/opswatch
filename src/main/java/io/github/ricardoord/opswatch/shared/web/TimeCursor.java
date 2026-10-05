package io.github.ricardoord.opswatch.shared.web;

import io.github.ricardoord.opswatch.shared.error.InvalidParameterException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The opaque cursor of a time series (docs/api/api-guidelines.md#por-cursor-keyset-series-temporales): Base64URL of
 * {@code {"c":"<instant>"}}, the instant of the last item already returned. It holds nothing else, so a cursor changed
 * by hand can only move within what the query already allows: the query always filters by the resource it authorized.
 * Anything that is not exactly what {@link #encode} writes is rejected.
 */
final class TimeCursor {

    /** Far longer than any cursor written here: a huge value is rejected before it is decoded. */
    private static final int MAX_LENGTH = 100;

    private static final Pattern JSON = Pattern.compile("\\{\"c\":\"([0-9T:.\\-Z]{20,30})\"}");

    static final String INVALID = "'cursor' is not valid: use the nextCursor of a previous page.";

    private TimeCursor() {}

    static String encode(Instant position) {
        String json = "{\"c\":\"" + position + "\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws InvalidParameterException if it is not a cursor written by {@link #encode} (400) */
    static Instant decode(String cursor) {
        if (cursor.length() > MAX_LENGTH) {
            throw new InvalidParameterException(INVALID);
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Matcher matcher = JSON.matcher(json);
            if (matcher.matches()) {
                return Instant.parse(matcher.group(1));
            }
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            // Reported below, like any other cursor that was not written here
        }
        throw new InvalidParameterException(INVALID);
    }
}
