package io.github.ricardoord.opswatch.shared.crypto;

/**
 * A ciphertext that does not decrypt: its key is no longer configured, it was altered, or it was copied from another row
 * or table. Never a client error: the request that reads it fails with a 500. The message never carries data.
 */
public class DecryptionFailedException extends RuntimeException {

    DecryptionFailedException(String message) {
        super(message);
    }
}
