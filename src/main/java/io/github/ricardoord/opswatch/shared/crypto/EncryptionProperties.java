package io.github.ricardoord.opswatch.shared.crypto;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The keys of {@link SecretCipher} (docs/devops/environments.md#seguridad). In deployed environments they come from
 * Docker secrets through {@code configtree}, locally from {@code secrets/encryption-dev-key} (scripts/dev-keys.sh), and
 * the tests generate their own. The application does not start, in any profile, without the active key or with a key
 * that is not 32 bytes: every message names the property, never the key.
 *
 * <p>The checks run when {@link SecretCipher} is built, not while binding: Spring Boot reports a binding failure with
 * the value of the last property it bound, which would be a key.
 *
 * @param keys AES-256 keys in Base64 by id, from 0 to 255: the id is the first byte of every ciphertext. A retired key
 *     stays while something is still encrypted with it
 * @param activeKeyId the key that encrypts anything new
 */
@ConfigurationProperties("opswatch.security.encryption")
public record EncryptionProperties(
        @DefaultValue Map<Integer, String> keys, @Nullable Integer activeKeyId) {

    static final int KEY_BYTES = 32;
    static final int MAX_KEY_ID = 255;

    private static final String PREFIX = "opswatch.security.encryption.";

    public EncryptionProperties {
        keys = Map.copyOf(keys);
    }

    /** @throws IllegalArgumentException if it is missing, or not among the keys */
    int requireActiveKeyId() {
        if (activeKeyId == null) {
            throw new IllegalArgumentException(PREFIX + "active-key-id must be set");
        }
        if (!keys.containsKey(activeKeyId)) {
            throw new IllegalArgumentException(PREFIX + "keys." + activeKeyId + " must be set: it is the active key");
        }
        return activeKeyId;
    }

    /** @throws IllegalArgumentException naming the first key that is wrong, never its value */
    Map<Integer, SecretKey> secretKeys() {
        Map<Integer, SecretKey> secretKeys = new HashMap<>();
        keys.forEach((id, value) -> secretKeys.put(id, new SecretKeySpec(decode(id, value), "AES")));
        return Map.copyOf(secretKeys);
    }

    /** The decoding error is not kept as the cause: its message quotes a character of the key. */
    private static byte[] decode(Integer id, String value) {
        if (id < 0 || id > MAX_KEY_ID) {
            throw new IllegalArgumentException(PREFIX + "keys." + id + ": the id must be between 0 and " + MAX_KEY_ID);
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException ex) {
            key = new byte[0];
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    PREFIX + "keys." + id + " must be a " + KEY_BYTES + "-byte AES key in Base64");
        }
        return key;
    }

    /** Never includes the keys: a startup failure report can print the whole object. */
    @Override
    public String toString() {
        return "EncryptionProperties[keyIds=" + new TreeSet<>(keys.keySet()) + ", activeKeyId=" + activeKeyId + "]";
    }
}
