package io.github.ricardoord.opswatch.monitoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.shared.error.InvalidParameterException;
import org.junit.jupiter.api.Test;

/** {@code q} of the listing as a {@code LIKE} pattern: the user's text never becomes a wildcard. */
class NamePatternTest {

    @Test
    void noQueryMatchesEveryName() {
        assertThat(MonitorService.namePattern(null)).isEqualTo("%");
        assertThat(MonitorService.namePattern("   ")).isEqualTo("%");
    }

    @Test
    void searchesAnywhereInTheName() {
        assertThat(MonitorService.namePattern("API")).isEqualTo("%API%");
    }

    @Test
    void escapesTheWildcardsAndTheEscapeCharacter() {
        assertThat(MonitorService.namePattern("50%_off\\")).isEqualTo("%50\\%\\_off\\\\%");
    }

    /** Characters, not UTF-16 units. */
    @Test
    void acceptsUpToAHundredCharacters() {
        String emoji = Character.toString(0x1F680);

        assertThat(MonitorService.namePattern(emoji.repeat(100))).hasSize(202);
        assertThatThrownBy(() -> MonitorService.namePattern("a".repeat(101)))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessage("q is longer than 100 characters.");
    }
}
