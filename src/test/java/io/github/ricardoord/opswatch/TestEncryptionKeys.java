package io.github.ricardoord.opswatch;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * The key of {@code SecretCipher} for the tests that start the application: a fresh AES-256 key per JVM, never the file
 * of {@code secrets/} (docs/devops/environments.md#perfiles). Imported by {@link IntegrationTest},
 * {@code ApplicationStartupIT} and {@code AuthRateLimitIT}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestEncryptionKeys {

    /** The first byte of every ciphertext the tests write. */
    public static final int ACTIVE_KEY_ID = 0;

    private static final String KEY = randomKey();

    @Bean
    DynamicPropertyRegistrar testEncryptionProperties() {
        return registry -> {
            registry.add("opswatch.security.encryption.keys." + ACTIVE_KEY_ID, () -> KEY);
            registry.add("opswatch.security.encryption.active-key-id", () -> ACTIVE_KEY_ID);
        };
    }

    /** 32 random bytes in Base64, as {@code openssl rand -base64 32} writes them. */
    public static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
