package io.github.ricardoord.opswatch.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ricardoord.opswatch.TestEncryptionKeys;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String OLD_KEY = TestEncryptionKeys.randomKey();
    private static final String NEW_KEY = TestEncryptionKeys.randomKey();
    private static final byte[] SECRET =
            "[{\"name\":\"Authorization\",\"value\":\"Bearer s3cr3t\"}]".getBytes(StandardCharsets.UTF_8);
    private static final String CONTEXT = "monitors.request_headers:0192-a";

    private final SecretCipher cipher = cipher(Map.of(7, OLD_KEY), 7);

    @Test
    void decryptsWhatItEncrypted() {
        byte[] sealed = cipher.encrypt(SECRET, CONTEXT);

        assertThat(cipher.decrypt(sealed, CONTEXT)).isEqualTo(SECRET);
    }

    @Test
    void writesTheKeyIdTheNonceTheCiphertextAndTheTag() {
        byte[] sealed = cipher.encrypt(SECRET, CONTEXT);

        assertThat(sealed).hasSize(1 + SecretCipher.NONCE_BYTES + SECRET.length + SecretCipher.TAG_BITS / 8);
        assertThat(Byte.toUnsignedInt(sealed[0])).isEqualTo(7);
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("s3cr3t");
    }

    /** A fresh nonce every time: the same secret never gives the same ciphertext. */
    @Test
    void neverEncryptsTheSameSecretTheSameWay() {
        assertThat(cipher.encrypt(SECRET, CONTEXT)).isNotEqualTo(cipher.encrypt(SECRET, CONTEXT));
    }

    @Test
    void rejectsAnAlteredCiphertext() {
        byte[] sealed = cipher.encrypt(SECRET, CONTEXT);
        for (int position : new int[] {1, 1 + SecretCipher.NONCE_BYTES, sealed.length - 1}) {
            byte[] altered = sealed.clone();
            altered[position] ^= 1;

            assertThatThrownBy(() -> cipher.decrypt(altered, CONTEXT))
                    .as("byte %d", position)
                    .isInstanceOf(DecryptionFailedException.class);
        }
    }

    /** The associated data binds a ciphertext to its row: copied to another one, it does not open. */
    @Test
    void rejectsOtherAssociatedData() {
        byte[] sealed = cipher.encrypt(SECRET, CONTEXT);

        assertThatThrownBy(() -> cipher.decrypt(sealed, "monitors.request_headers:0192-b"))
                .isInstanceOf(DecryptionFailedException.class)
                .hasMessageNotContaining("0192");
        assertThatThrownBy(() -> cipher.decrypt(sealed, "notification_channels.config:0192-a"))
                .isInstanceOf(DecryptionFailedException.class);
    }

    @Test
    void rejectsAKeyIdThatIsNotConfiguredAndATruncatedCiphertext() {
        byte[] sealed = cipher.encrypt(SECRET, CONTEXT);
        sealed[0] = 8;

        assertThatThrownBy(() -> cipher.decrypt(sealed, CONTEXT))
                .isInstanceOf(DecryptionFailedException.class)
                .hasMessage("The key 8 of the ciphertext is not configured");
        assertThatThrownBy(() -> cipher.decrypt(new byte[20], CONTEXT)).isInstanceOf(DecryptionFailedException.class);
    }

    /** Rotation: the old key stays configured, the new one encrypts, and both kinds of ciphertext open. */
    @Test
    void stillDecryptsWithAnOlderKeyAfterTheActiveOneChanges() {
        byte[] sealedBefore = cipher.encrypt(SECRET, CONTEXT);
        SecretCipher rotated = cipher(Map.of(7, OLD_KEY, 8, NEW_KEY), 8);

        byte[] sealedAfter = rotated.encrypt(SECRET, CONTEXT);

        assertThat(rotated.decrypt(sealedBefore, CONTEXT)).isEqualTo(SECRET);
        assertThat(Byte.toUnsignedInt(sealedAfter[0])).isEqualTo(8);
        assertThat(rotated.decrypt(sealedAfter, CONTEXT)).isEqualTo(SECRET);
    }

    /** A key id above 127 is still one unsigned byte. */
    @Test
    void handlesTheWholeRangeOfKeyIds() {
        SecretCipher highest = cipher(Map.of(255, NEW_KEY), 255);

        byte[] sealed = highest.encrypt(SECRET, CONTEXT);

        assertThat(Byte.toUnsignedInt(sealed[0])).isEqualTo(255);
        assertThat(highest.decrypt(sealed, CONTEXT)).isEqualTo(SECRET);
    }

    private static SecretCipher cipher(Map<Integer, String> keys, int activeKeyId) {
        return new SecretCipher(new EncryptionProperties(keys, activeKeyId));
    }
}
