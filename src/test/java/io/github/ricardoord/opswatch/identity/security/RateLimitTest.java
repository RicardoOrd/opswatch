package io.github.ricardoord.opswatch.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RateLimitTest {

    @Test
    void parsesCapacityAndPeriod() {
        assertThat(RateLimit.valueOf("10/1m")).isEqualTo(new RateLimit(10, Duration.ofMinutes(1)));
        assertThat(RateLimit.valueOf("5/15m")).isEqualTo(new RateLimit(5, Duration.ofMinutes(15)));
        assertThat(RateLimit.valueOf(" 30 / 1h ")).isEqualTo(new RateLimit(30, Duration.ofHours(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "10/", "/1m", "ten/1m", "10/a minute", "0/1m", "-1/1m", "10/0s", "10/-1m"})
    void rejectsAnythingElse(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RateLimit.valueOf(value))
                .withMessageContaining("like 10/1m")
                .withMessageContaining(value);
    }

    @Test
    void theDefaultsAreTheValuesOfThePropertyCatalog() {
        // docs/devops/environments.md#seguridad
        RateLimitProperties properties = bind(Map.of());

        assertThat(properties.loginPerIp()).isEqualTo(RateLimit.valueOf("10/1m"));
        assertThat(properties.loginPerEmail()).isEqualTo(RateLimit.valueOf("5/1m"));
        assertThat(properties.registerPerIp()).isEqualTo(RateLimit.valueOf("5/1h"));
        assertThat(properties.refreshPerIp()).isEqualTo(RateLimit.valueOf("30/1m"));
        assertThat(properties.passwordChangePerUser()).isEqualTo(RateLimit.valueOf("5/15m"));
    }

    @Test
    void bindsFromTheProperties() {
        RateLimitProperties properties = bind(Map.of("opswatch.security.rate-limit.login-per-ip", "20/30s"));

        assertThat(properties.loginPerIp()).isEqualTo(new RateLimit(20, Duration.ofSeconds(30)));
    }

    private static RateLimitProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("opswatch.security.rate-limit", RateLimitProperties.class);
    }
}
