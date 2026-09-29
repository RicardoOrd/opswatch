package io.github.ricardoord.opswatch.identity.web;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.PostgresTestcontainer;
import io.github.ricardoord.opswatch.TestJwtKeys;
import io.github.ricardoord.opswatch.shared.time.MutableClock;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The limits of the authentication endpoints through the real server, not MockMvc: the client address comes from
 * Tomcat's {@code RemoteIpValve}, which MockMvc skips. The server settings are those of application-deployed.yml, with
 * 127.0.0.2 standing for Caddy, the only trusted proxy, and 127.0.0.1 for any other host that reaches the application.
 *
 * <p>Its own Spring context: the catalog limits instead of the high ones of the {@code test} profile, and a clock that
 * the tests move forward.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "opswatch.security.rate-limit.login-per-ip=10/1m",
            "opswatch.security.rate-limit.login-per-email=5/1m",
            "opswatch.security.rate-limit.register-per-ip=5/1h",
            "opswatch.security.rate-limit.refresh-per-ip=30/1m",
            // What the environment sets in a deployment: the address of Caddy
            "server.tomcat.remoteip.internal-proxies=127.0.0.2/32",
        })
@TestPropertySource(
        locations = "classpath:application-deployed.yml",
        factory = AuthRateLimitIT.ServerSettingsOnly.class)
@Import({PostgresTestcontainer.class, TestJwtKeys.class, AuthRateLimitIT.ControlledClock.class})
@ActiveProfiles("test")
class AuthRateLimitIT {

    private static final String PASSWORD = "correct horse battery";
    private static final InetAddress PROXY = loopback(2);
    private static final HttpClient FROM_ANOTHER_HOST = HttpClient.newHttpClient();
    private static final HttpClient FROM_THE_PROXY =
            HttpClient.newBuilder().localAddress(PROXY).build();

    @LocalServerPort
    private int port;

    @Autowired
    private MutableClock clock;

    @Autowired
    private Environment environment;

    @BeforeEach
    void everyBucketIsFullAgain() {
        clock.advance(Duration.ofDays(1));
    }

    @Test
    void runsWithTheServerSettingsOfTheDeployedProfile() {
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("native");
    }

    @Test
    void theEleventhLoginInAMinuteFromOneAddressIsRejectedUntilATokenRefills() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(login(FROM_ANOTHER_HOST, uniqueEmail(), null).statusCode())
                    .isEqualTo(401);
        }

        assertRateLimited(login(FROM_ANOTHER_HOST, uniqueEmail(), null), "6");
        clock.advance(Duration.ofSeconds(6));
        assertThat(login(FROM_ANOTHER_HOST, uniqueEmail(), null).statusCode()).isEqualTo(401);
    }

    @Test
    void theSixthLoginInAMinuteAgainstOneEmailIsRejectedFromAnyAddressAndInAnyCase() throws Exception {
        String email = uniqueEmail();
        for (int i = 0; i < 5; i++) {
            String sameEmail = i % 2 == 0 ? email : email.toUpperCase(Locale.ROOT);
            assertThat(login(FROM_THE_PROXY, sameEmail, "203.0.113." + i).statusCode())
                    .isEqualTo(401);
        }

        assertRateLimited(login(FROM_THE_PROXY, email, "198.51.100.1"), "12");
    }

    @Test
    void theSixthRegistrationInAnHourFromOneAddressIsRejected() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(register(uniqueEmail()).statusCode()).isEqualTo(201);
        }

        assertRateLimited(register(uniqueEmail()), "720");
    }

    @Test
    void theThirtyFirstRefreshInAMinuteFromOneAddressIsRejected() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(refreshWithoutCookie().statusCode()).isEqualTo(401);
        }

        assertRateLimited(refreshWithoutCookie(), "2");
    }

    @Test
    void aForwardedForHeaderFromAnyOtherHostDoesNotChangeTheClientAddress() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(login(FROM_ANOTHER_HOST, uniqueEmail(), "198.51.100." + i)
                            .statusCode())
                    .isEqualTo(401);
        }

        assertRateLimited(login(FROM_ANOTHER_HOST, uniqueEmail(), "198.51.100.200"), "6");
    }

    @Test
    void aForwardedForHeaderFromTheProxyIsTheClientAddress() throws Exception {
        // Otherwise every client behind Caddy would share one bucket
        for (int i = 0; i < 15; i++) {
            assertThat(login(FROM_THE_PROXY, uniqueEmail(), "198.51.100." + i).statusCode())
                    .isEqualTo(401);
        }
    }

    private static void assertRateLimited(HttpResponse<String> response, String retryAfter) {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue(HttpHeaders.RETRY_AFTER)).hasValue(retryAfter);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("rate-limited");
    }

    private HttpResponse<String> login(HttpClient client, String email, @Nullable String forwardedFor)
            throws IOException, InterruptedException {
        String body = "{\"email\": \"%s\", \"password\": \"wrong password\"}".formatted(email);
        return client.send(post("/api/v1/auth/login", body, forwardedFor).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> register(String email) throws IOException, InterruptedException {
        String body = "{\"email\": \"%s\", \"displayName\": \"Ana\", \"password\": \"%s\"}".formatted(email, PASSWORD);
        return FROM_ANOTHER_HOST.send(post("/api/v1/auth/register", body, null).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> refreshWithoutCookie() throws IOException, InterruptedException {
        HttpRequest request = post("/api/v1/auth/refresh", "{}", null)
                .header(HttpHeaders.ORIGIN, TestJwtKeys.ISSUER)
                .build();
        return FROM_ANOTHER_HOST.send(request, BodyHandlers.ofString());
    }

    /** To 127.0.0.1 and not localhost, which could resolve to ::1 and fail to connect from the proxy's address. */
    private HttpRequest.Builder post(String path, String json, @Nullable String forwardedFor) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(BodyPublishers.ofString(json));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    private static InetAddress loopback(int lastByte) {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, (byte) lastByte});
        } catch (UnknownHostException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledClock {

        @Bean
        @Primary
        MutableClock controlledClock() {
            return new MutableClock(Instant.now());
        }
    }

    /**
     * Only the {@code server.*} settings of the file. The rest would change the whole test JVM: JSON logs, or Docker
     * secrets as configuration.
     */
    static class ServerSettingsOnly implements PropertySourceFactory {

        @Override
        public PropertySource<?> createPropertySource(@Nullable String name, EncodedResource resource) {
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(resource.getResource());
            Properties all = Objects.requireNonNull(yaml.getObject());
            Map<String, Object> server = new HashMap<>();
            for (String key : all.stringPropertyNames()) {
                if (key.startsWith("server.")) {
                    server.put(key, all.getProperty(key));
                }
            }
            return new MapPropertySource("server settings of " + resource.getResource(), server);
        }
    }
}
