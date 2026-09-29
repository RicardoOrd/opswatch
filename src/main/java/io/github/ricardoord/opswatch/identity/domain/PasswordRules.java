package io.github.ricardoord.opswatch.identity.domain;

import java.nio.charset.StandardCharsets;

/**
 * Length rules for passwords (docs/security/security-architecture.md#contraseñas): at least 12 characters, and at most
 * 72 bytes in UTF-8 because bcrypt ignores everything after the 72nd byte. No composition rules, following NIST SP
 * 800-63B.
 */
public final class PasswordRules {

    public static final int MIN_CHARACTERS = 12;
    public static final int MAX_UTF8_BYTES = 72;

    private PasswordRules() {}

    public static boolean accepts(String password) {
        return password.codePointCount(0, password.length()) >= MIN_CHARACTERS
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }
}
