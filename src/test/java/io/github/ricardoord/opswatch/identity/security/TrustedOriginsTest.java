package io.github.ricardoord.opswatch.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedOriginsTest {

    private final TrustedOrigins origins = new TrustedOrigins(
            jwt("https://api.opswatch.example/"), new CorsProperties(List.of("http://localhost:5173")));

    @Test
    void allowsTheOriginOfTheIssuerAndTheCorsOrigins() {
        assertThat(origins.allows("https://api.opswatch.example")).isTrue();
        assertThat(origins.allows("http://localhost:5173")).isTrue();
    }

    @Test
    void comparesOriginsAsBrowsersSerializeThem() {
        assertThat(origins.allows("HTTPS://API.OpsWatch.example")).isTrue();
        assertThat(origins.allows("https://api.opswatch.example:443")).isTrue();
    }

    @Test
    void rejectsAnyOtherOrigin() {
        assertThat(origins.allows("https://evil.example")).isFalse();
        assertThat(origins.allows("http://api.opswatch.example")).isFalse();
        assertThat(origins.allows("https://api.opswatch.example:8443")).isFalse();
        assertThat(origins.allows("https://api.opswatch.example.evil.example")).isFalse();
        assertThat(origins.allows("http://localhost:5174")).isFalse();
    }

    @Test
    void rejectsAMissingOpaqueOrMalformedOrigin() {
        assertThat(origins.allows(null)).isFalse();
        assertThat(origins.allows("")).isFalse();
        // What browsers send from sandboxed frames and file: pages
        assertThat(origins.allows("null")).isFalse();
        assertThat(origins.allows("not a url")).isFalse();
        assertThat(origins.allows("file:///etc/passwd")).isFalse();
    }

    @Test
    void refusesToStartWithAnIssuerThatIsNotAnHttpUrl() {
        assertThatThrownBy(() -> new TrustedOrigins(jwt("opswatch"), new CorsProperties(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static JwtProperties jwt(String issuer) {
        return new JwtProperties(issuer, "opswatch-api", Duration.ofMinutes(15), "unused", null);
    }
}
