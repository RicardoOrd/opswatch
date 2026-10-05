package io.github.ricardoord.opswatch.shared.web;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The {@code limit} and {@code cursor} of a list paginated by cursor (docs/api/api-guidelines.md#9-paginación), as a
 * controller parameter. {@link CursorQueryArgumentResolver} reads and checks both: the cursor arrives decoded.
 *
 * @param limit from 1 to {@link #MAX_LIMIT}
 * @param after the position the cursor carries, that of the last item already returned; null for the first page
 */
public record CursorQuery(int limit, @Nullable Instant after) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public CursorQuery {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be from 1 to " + MAX_LIMIT);
        }
    }

    /** One more than the limit: the extra row, if it comes, says there is a next page ({@link CursorPage#of}). */
    public int rowsToFetch() {
        return limit + 1;
    }
}
