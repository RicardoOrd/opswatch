package io.github.ricardoord.opswatch.notification.delivery;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signature of a webhook (docs/api/webhooks.md): {@code t=<timestamp>,v1=<HMAC-SHA256>}, where the HMAC is of
 * {@code <timestamp>.<body>} with the signing secret of the channel as the key, the whole {@code whsec_…} string in
 * UTF-8, in lower-case hex. The timestamp is in the signature so that a receiver can reject an old request replayed
 * with its signature (T-31).
 */
final class WebhookSigner {

    static final String SIGNATURE_HEADER = "X-OpsWatch-Signature";
    static final String VERSION_HEADER = "X-OpsWatch-Webhook-Version";
    static final String VERSION = "1";

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSigner() {}

    /**
     * @param timestamp Unix seconds when it is sent
     * @param body exactly the bytes that are sent
     */
    static String sign(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is always available", ex);
        }
    }
}
