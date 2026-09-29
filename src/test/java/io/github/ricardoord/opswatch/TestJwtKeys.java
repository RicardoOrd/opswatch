package io.github.ricardoord.opswatch;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Access token keys for the tests that start the application: a fresh RSA pair per JVM, never a file from
 * {@code secrets/} (docs/devops/environments.md#perfiles). Imported by {@link IntegrationTest} and
 * {@code ApplicationStartupIT}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtKeys {

    public static final String ISSUER = "https://opswatch.test";

    private static final String PRIVATE_KEY_PEM =
            pem("PRIVATE KEY", rsaKeyPair(2048).getPrivate());

    @Bean
    DynamicPropertyRegistrar testJwtProperties() {
        return registry -> {
            registry.add("opswatch.security.jwt.issuer", () -> ISSUER);
            registry.add("opswatch.security.jwt.private-key", () -> PRIVATE_KEY_PEM);
        };
    }

    public static KeyPair rsaKeyPair(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** PKCS#8 for a private key and X.509 for a public one: the formats of scripts/dev-keys.sh. */
    public static String pem(String type, Key key) {
        Base64.Encoder base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        return "-----BEGIN " + type + "-----\n" + base64.encodeToString(key.getEncoded()) + "\n-----END " + type
                + "-----\n";
    }
}
