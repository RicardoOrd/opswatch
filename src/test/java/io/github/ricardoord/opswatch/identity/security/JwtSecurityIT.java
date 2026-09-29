package io.github.ricardoord.opswatch.identity.security;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.aUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestJwtKeys;
import io.github.ricardoord.opswatch.identity.domain.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Forged and broken access tokens against the real decoder (threat T-04 of docs/security/threat-model.md). Every one
 * of them must end as the same {@code 401}, whatever the reason.
 */
@IntegrationTest
class JwtSecurityIT {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JwtKeys keys;

    @Autowired
    private AccessTokenIssuer issuer;

    @Autowired
    private UserRepository users;

    private UUID userId;

    @BeforeEach
    void createUser() {
        userId = users.saveAndFlush(aUser().build()).id();
    }

    @Test
    void acceptsATokenItIssued() {
        assertThat(me(issuer.issue(userId).value())).hasStatus(200);
    }

    @Test
    void acceptsAHandBuiltTokenWithOurKeyAndValidClaims() throws Exception {
        // Control for the tests below: each one breaks a single thing of this token
        assertThat(me(sign(validClaims().build()))).hasStatus(200);
    }

    @Test
    void toleratesThirtySecondsOfClockSkew() throws Exception {
        Instant expiredTenSecondsAgo = Instant.now().minusSeconds(10);
        Instant issuedAt = expiredTenSecondsAgo.minus(Duration.ofMinutes(15));

        assertThat(me(sign(validClaims()
                        .issueTime(Date.from(issuedAt))
                        .expirationTime(Date.from(expiredTenSecondsAgo))
                        .build())))
                .hasStatus(200);
    }

    @Test
    void rejectsAnAlteredSignature() throws Exception {
        String[] parts = sign(validClaims().build()).split("\\.");
        char[] signature = parts[2].toCharArray();
        int middle = signature.length / 2;
        signature[middle] = signature[middle] == 'A' ? 'B' : 'A';

        assertRejected(parts[0] + "." + parts[1] + "." + new String(signature));
    }

    @Test
    void rejectsAPayloadSwappedUnderAValidSignature() throws Exception {
        String[] original = sign(validClaims().build()).split("\\.");
        String[] other = sign(validClaims()
                        .subject(UUID.randomUUID().toString())
                        .build())
                .split("\\.");

        assertRejected(original[0] + "." + other[1] + "." + original[2]);
    }

    @Test
    void rejectsAnUnsignedToken() {
        assertRejected(new PlainJWT(validClaims().build()).serialize());
    }

    @Test
    void rejectsHs256SignedWithThePublicKey() throws Exception {
        // Algorithm confusion: a verifier that trusted the header would check this HMAC with the public key
        byte[] publicKey = keys.signingKey().toRSAPublicKey().getEncoded();
        String token = sign(
                new MACSigner(publicKey),
                JWSAlgorithm.HS256,
                keys.signingKey().getKeyID(),
                validClaims().build());

        assertRejected(token);
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        Instant issuedAt = Instant.now().minus(Duration.ofMinutes(16));

        assertRejected(sign(validClaims()
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(Duration.ofMinutes(15))))
                .build()));
    }

    @Test
    void rejectsATokenWithoutExpiry() throws Exception {
        assertRejected(sign(validClaims().expirationTime(null).build()));
    }

    @Test
    void rejectsAnotherIssuer() throws Exception {
        assertRejected(sign(validClaims().issuer("https://evil.example").build()));
    }

    @Test
    void rejectsAnotherAudience() throws Exception {
        assertRejected(sign(validClaims().audience("another-api").build()));
    }

    @Test
    void rejectsASubjectThatIsNotAUserId() throws Exception {
        assertRejected(sign(validClaims().subject("admin").build()));
    }

    @Test
    void rejectsAnUnknownKeyId() throws Exception {
        RSAKey foreign = new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();

        assertRejected(sign(
                new RSASSASigner(foreign),
                JWSAlgorithm.RS256,
                foreign.getKeyID(),
                validClaims().build()));
    }

    @Test
    void rejectsAForeignKeyThatClaimsOurKeyId() throws Exception {
        RSAKey foreign = new RSAKeyGenerator(2048).generate();

        assertRejected(sign(
                new RSASSASigner(foreign),
                JWSAlgorithm.RS256,
                keys.signingKey().getKeyID(),
                validClaims().build()));
    }

    private JWTClaimsSet.Builder validClaims() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return new JWTClaimsSet.Builder()
                .issuer(TestJwtKeys.ISSUER)
                .audience("opswatch-api")
                .subject(userId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
                .jwtID(UUID.randomUUID().toString());
    }

    private String sign(JWTClaimsSet claims) throws JOSEException {
        RSAKey key = keys.signingKey();
        return sign(new RSASSASigner(key), JWSAlgorithm.RS256, key.getKeyID(), claims);
    }

    private static String sign(JWSSigner signer, JWSAlgorithm algorithm, String keyId, JWTClaimsSet claims)
            throws JOSEException {
        JWSHeader header = new JWSHeader.Builder(algorithm)
                .keyID(keyId)
                .type(JOSEObjectType.JWT)
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(signer);
        return jwt.serialize();
    }

    private void assertRejected(String token) {
        MvcTestResult result = me(token);

        assertThat(result).hasStatus(401).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("unauthenticated");
        assertThat(result).headers().hasValue(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
    }

    private MvcTestResult me(String token) {
        return mvc.get()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }
}
