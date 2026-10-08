package io.github.ricardoord.opswatch.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.shared.ratelimit.RateLimit;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RateLimitPropertiesTest {

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
