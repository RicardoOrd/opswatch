package io.github.ricardoord.opswatch.identity.security;

import static io.github.ricardoord.opswatch.TestJwtKeys.pem;
import static io.github.ricardoord.opswatch.TestJwtKeys.rsaKeyPair;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;

class JwtKeysTest {

    private static final KeyPair CURRENT = rsaKeyPair(2048);
    private static final KeyPair PREVIOUS = rsaKeyPair(2048);

    @Test
    void derivesThePublicKeyAndTheKeyIdFromThePrivateKey() throws Exception {
        JwtKeys keys = JwtKeys.fromPem(pem("PRIVATE KEY", CURRENT.getPrivate()), null);

        RSAKey expected = new RSAKey.Builder((RSAPublicKey) CURRENT.getPublic()).build();
        assertThat(keys.signingKey().toRSAPublicKey()).isEqualTo(CURRENT.getPublic());
        assertThat(keys.signingKey().getKeyID())
                .isEqualTo(expected.computeThumbprint().toString());
        assertThat(keys.signingKey().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(keys.signingKey().isPrivate()).isTrue();
    }

    @Test
    void verifiesWithPublicKeysOnly() {
        JwtKeys keys = JwtKeys.fromPem(pem("PRIVATE KEY", CURRENT.getPrivate()), null);

        assertThat(keys.verificationKeys().getKeys()).singleElement().satisfies(key -> {
            assertThat(key.isPrivate()).isFalse();
            assertThat(key.getKeyID()).isEqualTo(keys.signingKey().getKeyID());
        });
    }

    @Test
    void keepsThePreviousPublicKeyDuringARotation() {
        JwtKeys keys =
                JwtKeys.fromPem(pem("PRIVATE KEY", CURRENT.getPrivate()), pem("PUBLIC KEY", PREVIOUS.getPublic()));

        assertThat(keys.verificationKeys().getKeys())
                .hasSize(2)
                .allSatisfy(key -> assertThat(key.getAlgorithm()).isEqualTo(JWSAlgorithm.RS256))
                .extracting(JWK::getKeyID)
                .doesNotHaveDuplicates()
                .contains(keys.signingKey().getKeyID());
    }

    @Test
    void rejectsAPrivateKeyShorterThan2048Bits() {
        String weak = pem("PRIVATE KEY", rsaKeyPair(1024).getPrivate());

        assertThatThrownBy(() -> JwtKeys.fromPem(weak, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("opswatch.security.jwt.private-key must be an RSA key of at least 2048 bits");
    }

    @Test
    void rejectsAPreviousPublicKeyShorterThan2048Bits() {
        String weak = pem("PUBLIC KEY", rsaKeyPair(1024).getPublic());

        assertThatThrownBy(() -> JwtKeys.fromPem(pem("PRIVATE KEY", CURRENT.getPrivate()), weak))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("opswatch.security.jwt.previous-public-key must be an RSA key of at least 2048 bits");
    }

    @Test
    void namesThePropertyButNeverPrintsTheKeyWhenItIsCorrupt() {
        String complete = pem("PRIVATE KEY", CURRENT.getPrivate());
        String broken = complete.substring(0, complete.length() / 2) + "\n-----END PRIVATE KEY-----\n";

        assertThatThrownBy(() -> JwtKeys.fromPem(broken, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("opswatch.security.jwt.private-key is not a valid RSA key in PEM")
                .satisfies(ex -> assertThat(String.valueOf(ex.getCause())).doesNotContain(broken.substring(40, 80)));
    }

    @Test
    void rejectsAPublicKeyWhereThePrivateKeyGoes() {
        String publicKey = pem("PUBLIC KEY", CURRENT.getPublic());

        assertThatThrownBy(() -> JwtKeys.fromPem(publicKey, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("opswatch.security.jwt.private-key is not a valid RSA key in PEM");
    }
}
