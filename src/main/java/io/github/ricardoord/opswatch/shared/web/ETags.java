package io.github.ricardoord.opswatch.shared.web;

import io.github.ricardoord.opswatch.shared.error.PreconditionFailedException;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Versions of editable resources in {@code ETag} and {@code If-Match} (docs/api/api-guidelines.md#5-headers): the
 * {@code @Version} of the row, as a strong entity tag ({@code "3"}).
 */
public final class ETags {

    private ETags() {}

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /**
     * {@code If-Match} is optional: without it, the {@code @Version} of the entity still stops a concurrent write (409).
     * When sent, it must list the current version or be {@code *}. Comparison is strong (RFC 9110): a weak tag
     * ({@code W/"3"}) never matches.
     *
     * @throws PreconditionFailedException if the header is sent and does not match (412)
     */
    public static void requireMatch(@Nullable String ifMatch, long currentVersion) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return;
        }
        String current = of(currentVersion);
        boolean matches = Arrays.stream(ifMatch.split(","))
                .map(String::strip)
                .anyMatch(tag -> tag.equals("*") || tag.equals(current));
        if (!matches) {
            throw new PreconditionFailedException(
                    "If-Match does not match the current version of the resource. Read it again and retry.");
        }
    }
}
