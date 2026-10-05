package io.github.ricardoord.opswatch.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.shared.error.InvalidParameterException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The page of a time series and its opaque cursor: what is written comes back, and nothing else gets in. */
class CursorPageTest {

    private static final Instant NEWEST = Instant.parse("2026-09-28T10:03:00.123456Z");

    @Test
    void aCursorCarriesTheInstantToTheMicrosecond() {
        assertThat(TimeCursor.decode(TimeCursor.encode(NEWEST))).isEqualTo(NEWEST);
        Instant whole = Instant.parse("2026-09-28T10:03:00Z");
        assertThat(TimeCursor.decode(TimeCursor.encode(whole))).isEqualTo(whole);
    }

    /** URL-safe and without padding: it goes in a query string as it is. */
    @Test
    void aCursorIsUrlSafe() {
        assertThat(TimeCursor.encode(NEWEST)).matches("[A-Za-z0-9_-]+");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-cursor", "eyJjIjoi", "%%%", "{\"c\":\"2026-09-28T10:03:00Z\"}"})
    void rejectsWhatIsNotACursor(String cursor) {
        assertThatThrownBy(() -> TimeCursor.decode(cursor))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessage(TimeCursor.INVALID);
    }

    /** Well-formed Base64URL of something that is not exactly what {@link TimeCursor#encode} writes. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"c\":\"yesterday\"}",
                "{\"c\":\"2026-13-28T10:03:00Z\"}",
                "{\"c\":\"2026-09-28T10:03:00Z\",\"m\":\"0192\"}",
                "{\"m\":\"0192\"}",
                "{\"c\":\"2026-09-28T10:03:00Z\"} ",
                "[\"2026-09-28T10:03:00Z\"]"
            })
    void rejectsACursorChangedByHand(String json) {
        String cursor = Base64.getUrlEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> TimeCursor.decode(cursor)).isInstanceOf(InvalidParameterException.class);
    }

    @Test
    void rejectsAHugeCursorBeforeDecodingIt() {
        assertThatThrownBy(() -> TimeCursor.decode("A".repeat(10_000))).isInstanceOf(InvalidParameterException.class);
    }

    @Test
    void aPageWithNoMoreRowsThanTheLimitIsTheLast() {
        CursorPage<Instant> page = CursorPage.of(List.of(NEWEST, NEWEST.minusSeconds(60)), 2, Function.identity());

        assertThat(page.items()).containsExactly(NEWEST, NEWEST.minusSeconds(60));
        assertThat(page.nextCursor()).isNull();
    }

    /** The extra row is not shown: it only says that there is a next page, which starts after the last item shown. */
    @Test
    void oneRowMoreThanTheLimitGivesTheCursorOfTheLastItemShown() {
        List<Instant> rows = List.of(NEWEST, NEWEST.minusSeconds(60), NEWEST.minusSeconds(120));

        CursorPage<Instant> page = CursorPage.of(rows, 2, Function.identity());

        assertThat(page.items()).containsExactly(NEWEST, NEWEST.minusSeconds(60));
        assertThat(page.nextCursor()).isNotNull();
        assertThat(TimeCursor.decode(page.nextCursor())).isEqualTo(NEWEST.minusSeconds(60));
    }

    @Test
    void mappingKeepsTheCursor() {
        CursorPage<Instant> page = CursorPage.of(List.of(NEWEST, NEWEST.minusSeconds(60)), 1, Function.identity());

        CursorPage<String> mapped = page.map(Instant::toString);

        assertThat(mapped.items()).containsExactly(NEWEST.toString());
        assertThat(mapped.nextCursor()).isEqualTo(page.nextCursor());
    }

    @Test
    void theLimitGoesFromOneToTwoHundred() {
        assertThat(new CursorQuery(200, null).rowsToFetch()).isEqualTo(201);
        assertThatThrownBy(() -> new CursorQuery(0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CursorQuery(201, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
