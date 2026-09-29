package io.github.ricardoord.opswatch.shared.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class IdGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00.123Z");

    @Test
    void generatesVersion7WithTheRfc9562Variant() {
        UUID id = new IdGenerator(fixedAt(NOW)).next();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void startsWithTheUnixTimestampInMilliseconds() {
        UUID id = new IdGenerator(fixedAt(NOW)).next();

        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
    }

    @Test
    void laterIdsSortAfterEarlierOnes() {
        UUID earlier = new IdGenerator(fixedAt(NOW)).next();
        UUID later = new IdGenerator(fixedAt(NOW.plusMillis(1))).next();

        // PostgreSQL compares uuid values byte by byte, as unsigned numbers
        assertThat(Long.compareUnsigned(earlier.getMostSignificantBits(), later.getMostSignificantBits()))
                .isNegative();
    }

    @Test
    void idsFromTheSameMillisecondAreStillUnique() {
        IdGenerator ids = new IdGenerator(fixedAt(NOW));

        Set<UUID> generated = new HashSet<>();
        IntStream.range(0, 10_000).forEach(i -> generated.add(ids.next()));

        assertThat(generated).hasSize(10_000);
    }

    private static Clock fixedAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
