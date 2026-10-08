package io.github.ricardoord.opswatch.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookSignerTest {

    /** Low entropy on purpose: a real-looking secret in a test would trip the secrets scan. */
    private static final String SECRET = "whsec_" + "0".repeat(43);

    private static final byte[] BODY = "{\"id\":\"0192\",\"type\":\"TEST\"}".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    /** From openssl, not from Java: see {@link #matchesAKnownVector()}. */
    private static final String KNOWN_HMAC = "9d53c3b31bfc61efdb839aa6ce54c7151dcf5aec2a797ede75c3afd701e41f13";

    @Test
    void signsTheTimestampAndTheBodyAsTheGuideSays() {
        String signature = WebhookSigner.sign(SECRET, NOW.getEpochSecond(), BODY);

        assertThat(signature).matches("t=" + NOW.getEpochSecond() + ",v1=[0-9a-f]{64}");
        assertThat(WebhookSignatureVerifier.verify(SECRET, signature, BODY, NOW, TOLERANCE))
                .isTrue();
    }

    /** A known vector, so that a change of the scheme cannot pass by changing the signer and the verifier together. */
    @Test
    void matchesAKnownVector() {
        // printf '%s' '1791453600.{"id":"0192","type":"TEST"}' | openssl dgst -sha256 -hmac "whsec_$(printf '0%.0s'
        // $(seq 1 43))"
        assertThat(WebhookSigner.sign(SECRET, 1_791_453_600L, BODY)).isEqualTo("t=1791453600,v1=" + KNOWN_HMAC);
    }

    @Test
    void anotherSecretAnotherBodyOrAnOldTimestampDoNotVerify() {
        String signature = WebhookSigner.sign(SECRET, NOW.getEpochSecond(), BODY);

        assertThat(WebhookSignatureVerifier.verify(SECRET + "x", signature, BODY, NOW, TOLERANCE))
                .isFalse();
        assertThat(WebhookSignatureVerifier.verify(
                        SECRET, signature, "{\"id\":\"0193\"}".getBytes(StandardCharsets.UTF_8), NOW, TOLERANCE))
                .isFalse();
        // A replay six minutes later
        assertThat(WebhookSignatureVerifier.verify(SECRET, signature, BODY, NOW.plus(Duration.ofMinutes(6)), TOLERANCE))
                .isFalse();
    }
}
