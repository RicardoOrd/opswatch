package io.github.ricardoord.opswatch.identity.security;

import io.github.ricardoord.opswatch.shared.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Signs access tokens. They carry who the user is and nothing about what they may do: no roles and no organizations,
 * so authorization is always decided against the database (ADR-004).
 */
@Component
public class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final JwtKeys keys;
    private final JwtProperties properties;
    private final IdGenerator ids;
    private final Clock clock;

    public AccessTokenIssuer(JwtEncoder encoder, JwtKeys keys, JwtProperties properties, IdGenerator ids, Clock clock) {
        this.encoder = encoder;
        this.keys = keys;
        this.properties = properties;
        this.ids = ids;
        this.clock = clock;
    }

    public AccessToken issue(UUID userId) {
        // JWT times are whole seconds
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(properties.accessTokenTtl()))
                .id(ids.next().toString())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(keys.signingKey().getKeyID())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, properties.accessTokenTtl());
    }
}
