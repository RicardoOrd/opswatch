package io.github.ricardoord.opswatch.monitoring.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.egress.RequestHeader;
import io.github.ricardoord.opswatch.monitoring.domain.ProbeMethod;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProbeRequestTest {

    /** It ends up in logs and in the messages of failed assertions. */
    @Test
    void printsNoSecret() {
        ProbeRequest request = new ProbeRequest(
                URI.create("https://user:password@api.example.com:8443/health?token=query-secret"),
                ProbeMethod.GET,
                List.of(new RequestHeader("Authorization", "Bearer header-secret")),
                Duration.ofSeconds(5),
                true);

        assertThat(request.toString())
                .contains("https://api.example.com:8443/health", "Authorization")
                .doesNotContain("password", "query-secret", "header-secret");
    }
}
