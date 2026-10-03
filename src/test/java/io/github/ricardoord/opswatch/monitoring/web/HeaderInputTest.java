package io.github.ricardoord.opswatch.monitoring.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HeaderInputTest {

    /** A validation report or a log line at TRACE prints the whole request. */
    @Test
    void neverPrintsTheValueNorDoTheRequestsThatCarryIt() {
        HeaderInput header = new HeaderInput("Authorization", "Bearer s3cr3t");
        CreateMonitorRequest create = new CreateMonitorRequest(
                "Payments API",
                "https://api.example.com/health",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(header));

        assertThat(header.toString()).isEqualTo("HeaderInput[name=Authorization, value=<redacted>]");
        assertThat(create.toString()).contains("Authorization").doesNotContain("s3cr3t");
    }

    /** Spaces around a value are not part of it (RFC 9110). */
    @Test
    void stripsTheValueButNotTheName() {
        assertThat(new HeaderInput(" X-Api-Key", "  abc \t").toHeader()).satisfies(converted -> {
            assertThat(converted.name()).isEqualTo(" X-Api-Key");
            assertThat(converted.value()).isEqualTo("abc");
        });
    }
}
