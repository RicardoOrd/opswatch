package io.github.ricardoord.opswatch.shared.web;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Cursor pagination contract of the API, for time series (docs/api/api-guidelines.md#por-cursor-keyset-series-temporales):
 * newest first, and a {@code nextCursor} to ask for the page after this one.
 *
 * @param nextCursor null when there is nothing more
 */
public record CursorPage<T>(List<T> items, @Nullable String nextCursor) {

    public CursorPage {
        items = List.copyOf(items);
    }

    /**
     * @param rows what the query found when asked for {@code limit + 1}, newest first: one more than {@code limit} means
     *     there is a next page, which starts after the last item of this one
     * @param position the instant of a row, unique in the series
     */
    public static <T> CursorPage<T> of(List<T> rows, int limit, Function<? super T, Instant> position) {
        if (rows.size() <= limit) {
            return new CursorPage<>(rows, null);
        }
        List<T> items = rows.subList(0, limit);
        return new CursorPage<>(items, TimeCursor.encode(position.apply(items.getLast())));
    }

    public <R> CursorPage<R> map(Function<? super T, ? extends R> mapper) {
        return new CursorPage<>(items.stream().<R>map(mapper).toList(), nextCursor);
    }
}
