package io.github.ricardoord.opswatch.identity.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.util.StringUtils;

/**
 * RSA keys of the access tokens. The key id ({@code kid}) of each key is its RFC 7638 thumbprint, computed rather than
 * configured, so a key and its id can never disagree.
 *
 * <p>Rotation (docs/security/security-architecture.md#contenido-del-jwt): the new private key signs, and the previous
 * public key keeps verifying the tokens it signed until they expire, 15 minutes at most.
 */
public final class JwtKeys {

    static final int MIN_KEY_BITS = 2048;

    private final RSAKey signingKey;
    private final JWKSet verificationKeys;

    private JwtKeys(RSAKey signingKey, JWKSet verificationKeys) {
        this.signingKey = signingKey;
        this.verificationKeys = verificationKeys;
    }

    /**
     * @throws IllegalStateException if a key is not valid PEM or is shorter than 2048 bits. The message names the
     *     property, never the key.
     */
    public static JwtKeys fromPem(String privateKeyPem, @Nullable String previousPublicKeyPem) {
        RSAPrivateKey privateKey = parse(RsaKeyConverters.pkcs8(), privateKeyPem, "private-key");
        if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
            throw new IllegalStateException("opswatch.security.jwt.private-key must be a complete RSA private key");
        }
        RSAKey signingKey = rsaKey(requireStrength(publicHalfOf(crtKey), "private-key"), privateKey);

        List<JWK> verificationKeys = new ArrayList<>();
        verificationKeys.add(signingKey.toPublicJWK());
        if (StringUtils.hasText(previousPublicKeyPem)) {
            RSAPublicKey previous = parse(RsaKeyConverters.x509(), previousPublicKeyPem, "previous-public-key");
            verificationKeys.add(rsaKey(requireStrength(previous, "previous-public-key"), null));
        }
        return new JwtKeys(signingKey, new JWKSet(verificationKeys));
    }

    /** Private and public halves of the key that signs new tokens. */
    public RSAKey signingKey() {
        return signingKey;
    }

    /** Public keys only: the current one and, during a rotation, the previous one. */
    public JWKSet verificationKeys() {
        return verificationKeys;
    }

    /** RS256 is declared on every key, so neither the encoder nor the decoder can pair it with another algorithm. */
    private static RSAKey rsaKey(RSAPublicKey publicKey, @Nullable RSAPrivateKey privateKey) {
        RSAKey.Builder builder =
                new RSAKey.Builder(publicKey).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256);
        if (privateKey != null) {
            builder.privateKey(privateKey);
        }
        try {
            return builder.keyIDFromThumbprint().build();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Cannot compute the key id of an access token key", ex);
        }
    }

    private static <K> K parse(Converter<InputStream, K> converter, String pem, String property) {
        try {
            K key = converter.convert(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            if (key == null) {
                throw new IllegalArgumentException("No key found");
            }
            return key;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("opswatch.security.jwt." + property + " is not a valid RSA key in PEM", ex);
        }
    }

    private static RSAPublicKey publicHalfOf(RSAPrivateCrtKey privateKey) {
        try {
            var spec = new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent());
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot derive the public key from opswatch.security.jwt.private-key", ex);
        }
    }

    private static RSAPublicKey requireStrength(RSAPublicKey key, String property) {
        if (key.getModulus().bitLength() < MIN_KEY_BITS) {
            throw new IllegalStateException(
                    "opswatch.security.jwt." + property + " must be an RSA key of at least " + MIN_KEY_BITS + " bits");
        }
        return key;
    }
}
