package io.github.ricardoord.opswatch.notification.delivery;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A receiver's verification, written from docs/api/webhooks.md alone and never from {@link WebhookSigner}: if the two
 * disagree, the guide or the signer is wrong.
 */
final class WebhookSignatureVerifier {

    private WebhookSignatureVerifier() {}

    /**
     * @param header the value of {@code X-OpsWatch-Signature}
     * @param body the raw bytes received, before any JSON parsing
     * @param tolerance how old a signature may be
     * @return whether the signature is of this body with this secret, and recent enough
     */
    static boolean verify(String secret, String header, byte[] body, Instant now, Duration tolerance) {
        Map<String, String> parts = new LinkedHashMap<>();
        for (String part : header.split(",")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                parts.put(
                        part.substring(0, equals).strip(),
                        part.substring(equals + 1).strip());
            }
        }
        String timestamp = parts.get("t");
        String received = parts.get("v1");
        if (timestamp == null || received == null) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(seconds), now).abs().compareTo(tolerance) > 0) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signed =
                    (timestamp + "." + new String(body, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            byte[] expected = mac.doFinal(signed);
            // Constant time, as the guide asks
            return MessageDigest.isEqual(expected, HexFormat.of().parseHex(received));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            return false;
        }
    }
}
