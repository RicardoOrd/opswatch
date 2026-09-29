package io.github.ricardoord.opswatch.shared.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Identifiers for every entity: UUID version 7 (RFC 9562), a 48-bit Unix timestamp in milliseconds followed by 74
 * random bits. Time-ordered, so B-tree inserts stay local, and hard to guess.
 *
 * <p>Entities receive their id when they are built, not when they are saved, so {@code equals}, {@code hashCode},
 * events and {@code Location} headers never depend on a flush. See docs/database/database-design.md#3-uuid-o-bigint.
 * Ids created within the same millisecond are not ordered among themselves; RFC 9562 allows it.
 */
@Component
public class IdGenerator {

    private static final long VERSION_7 = 0x7000L;
    private static final long RFC_9562_VARIANT = 0x8000_0000_0000_0000L;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public IdGenerator(Clock clock) {
        this.clock = clock;
    }

    public UUID next() {
        long timestamp = clock.millis() & 0xFFFF_FFFF_FFFFL;
        long mostSignificant = (timestamp << 16) | VERSION_7 | (random.nextInt() & 0x0FFFL);
        long leastSignificant = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | RFC_9562_VARIANT;
        return new UUID(mostSignificant, leastSignificant);
    }
}
