package io.github.ricardoord.opswatch.identity.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Issues and validates access tokens (ADR-004): RS256 only, with the checks of
 * docs/security/security-architecture.md#contenido-del-jwt. The Resource Server of Spring Security uses the decoder;
 * there is no hand-written JWT filter.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    @Bean
    JwtKeys jwtKeys(JwtProperties properties) {
        return JwtKeys.fromPem(properties.privateKey(), properties.previousPublicKey());
    }

    @Bean
    JwtEncoder jwtEncoder(JwtKeys keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeys keys, JwtProperties properties, Clock clock) {
        var processor = new DefaultJWTProcessor<SecurityContext>();
        // Only RS256 and only our keys: "none", HS256 and any other algorithm fail before the signature is checked
        processor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, new ImmutableJWKSet<>(keys.verificationKeys())));
        // The claims are checked by the validators below, with the injected clock, not by Nimbus
        processor.setJWTClaimsSetVerifier((claims, context) -> {});

        var decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(validator(properties, clock));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(JwtProperties properties, Clock clock) {
        var timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(
                new JwtClaimValidator<>(JwtClaimNames.EXP, Objects::nonNull),
                timestamps,
                new JwtIssuerValidator(properties.issuer()),
                new JwtClaimValidator<@Nullable Collection<String>>(
                        JwtClaimNames.AUD, audience -> audience != null && audience.contains(properties.audience())),
                new JwtClaimValidator<@Nullable String>(JwtClaimNames.SUB, JwtConfiguration::isUserId));
    }

    private static boolean isUserId(@Nullable String subject) {
        if (subject == null) {
            return false;
        }
        try {
            UUID.fromString(subject);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
