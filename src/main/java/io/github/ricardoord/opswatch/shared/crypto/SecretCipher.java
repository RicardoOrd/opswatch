package io.github.ricardoord.opswatch.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for the secrets kept in the database (docs/security/security-architecture.md#cifrado-de-datos-sensibles-en-la-base-de-datos).
 * Format: {@code keyId (1 byte) ‖ nonce (12 bytes) ‖ ciphertext ‖ tag (16 bytes)}.
 *
 * <p>Every encryption draws a fresh random nonce. A nonce must never repeat under one key: with 12 random bytes the
 * practical bound is about 2³² encryptions per key, far beyond what V1 writes. The key id lets keys rotate: anything new
 * is encrypted with the active key, and a ciphertext names the key that opens it.
 */
@Component
@EnableConfigurationProperties(EncryptionProperties.class)
public class SecretCipher {

    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int HEADER_BYTES = 1 + NONCE_BYTES;

    private final Map<Integer, SecretKey> keys;
    private final int activeKeyId;
    private final SecureRandom random = new SecureRandom();

    /** @throws IllegalArgumentException if the active key is missing or a key is not 32 bytes: the application stops */
    public SecretCipher(EncryptionProperties properties) {
        this.activeKeyId = properties.requireActiveKeyId();
        this.keys = properties.secretKeys();
    }

    /**
     * @param associatedData what the secret is and whose, such as {@code monitors.request_headers:<monitorId>}. It is
     *     not stored but authenticated: decrypting needs the same, so a ciphertext copied to another row or another
     *     table does not decrypt
     * @return {@code keyId ‖ nonce ‖ ciphertext ‖ tag}
     */
    public byte[] encrypt(byte[] plaintext, String associatedData) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), nonce, associatedData);
            byte[] sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(HEADER_BYTES + sealed.length)
                    .put((byte) activeKeyId)
                    .put(nonce)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-GCM encryption failed", ex);
        }
    }

    /**
     * @param associatedData the same as on encrypting
     * @throws DecryptionFailedException if the key is not configured, or the ciphertext was altered or belongs to other
     *     associated data
     */
    public byte[] decrypt(byte[] ciphertext, String associatedData) {
        if (ciphertext.length < HEADER_BYTES + TAG_BITS / 8) {
            throw new DecryptionFailedException("The ciphertext is too short");
        }
        int keyId = Byte.toUnsignedInt(ciphertext[0]);
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new DecryptionFailedException("The key " + keyId + " of the ciphertext is not configured");
        }
        byte[] nonce = Arrays.copyOfRange(ciphertext, 1, HEADER_BYTES);
        try {
            return cipher(Cipher.DECRYPT_MODE, key, nonce, associatedData)
                    .doFinal(ciphertext, HEADER_BYTES, ciphertext.length - HEADER_BYTES);
        } catch (AEADBadTagException ex) {
            throw new DecryptionFailedException("The ciphertext was altered or belongs to other associated data");
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-GCM decryption failed", ex);
        }
    }

    /** A new instance every time: {@link Cipher} is not safe to share between threads. */
    private static Cipher cipher(int mode, SecretKey key, byte[] nonce, String associatedData)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
