package io.github.ricardoord.opswatch.notification.application;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * The signing secrets of webhooks (docs/security/ssrf-protection.md#6-webhooks): 32 random bytes from the server,
 * never chosen by the user, in Base64URL after a {@code whsec_} prefix that tells what it is when it turns up in a log
 * or a repository.
 */
@Component
class SigningSecrets {

    static final String PREFIX = "whsec_";
    static final int BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    String next() {
        byte[] secret = new byte[BYTES];
        random.nextBytes(secret);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }
}
