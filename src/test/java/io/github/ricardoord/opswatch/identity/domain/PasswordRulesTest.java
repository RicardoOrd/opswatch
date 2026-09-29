package io.github.ricardoord.opswatch.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PasswordRulesTest {

    @Test
    void requiresAtLeastTwelveCharacters() {
        assertThat(PasswordRules.accepts("a".repeat(11))).isFalse();
        assertThat(PasswordRules.accepts("a".repeat(12))).isTrue();
    }

    @Test
    void countsCharactersNotUtf16Units() {
        // Each emoji is one character but two UTF-16 units: six of them are not twelve characters
        String sixEmojis = "🔒".repeat(6);
        assertThat(sixEmojis.length()).isEqualTo(12);

        assertThat(PasswordRules.accepts(sixEmojis)).isFalse();
        assertThat(PasswordRules.accepts("🔒".repeat(12))).isTrue();
    }

    @Test
    void acceptsUpTo72BytesInUtf8() {
        String seventyTwoBytes = "é".repeat(36);
        assertThat(seventyTwoBytes.getBytes(StandardCharsets.UTF_8)).hasSize(72);

        assertThat(PasswordRules.accepts(seventyTwoBytes)).isTrue();
        assertThat(PasswordRules.accepts(seventyTwoBytes + "a")).isFalse();
    }

    @Test
    void acceptsSpacesAndAnyCharacter() {
        assertThat(PasswordRules.accepts("correct horse battery")).isTrue();
    }
}
