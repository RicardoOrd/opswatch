package io.github.ricardoord.opswatch.identity.security;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Access token settings (docs/devops/environments.md#seguridad). Keys are PEM text: in deployed environments they come
 * from Docker secrets through {@code configtree}, locally from {@code secrets/} (scripts/dev-keys.sh), and the tests
 * generate their own.
 *
 * @param privateKey RSA private key in PKCS#8 PEM, 2048 bits or more. Signs the tokens; its public half verifies them
 * @param previousPublicKey during a key rotation, the public key that signed the tokens still in circulation
 */
@Validated
@ConfigurationProperties("opswatch.security.jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @DefaultValue("opswatch-api") @NotBlank String audience,
        @DefaultValue("15m") Duration accessTokenTtl,
        @NotBlank String privateKey,
        @Nullable String previousPublicKey) {

    /** Never includes the keys: a startup failure report can print the whole object. */
    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer + ", audience=" + audience + ", accessTokenTtl=" + accessTokenTtl
                + ", privateKey=<redacted>]";
    }
}
