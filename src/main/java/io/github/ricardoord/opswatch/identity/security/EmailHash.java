package io.github.ricardoord.opswatch.identity.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * How security events name an account (docs/devops/observability.md#eventos-de-seguridad): enough to link events
 * about the same email without writing it down.
 */
public final class EmailHash {

    private EmailHash() {}

    /** The first 8 bytes of the SHA-256 of the normalized email, in hex. */
    public static String of(String normalizedEmail) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(normalizedEmail.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is always available", ex);
        }
    }
}
