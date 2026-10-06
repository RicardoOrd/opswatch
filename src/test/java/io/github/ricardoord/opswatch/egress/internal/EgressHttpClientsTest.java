package io.github.ricardoord.opswatch.egress.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.ricardoord.opswatch.egress.BlockedTargetException;
import io.github.ricardoord.opswatch.egress.EgressClientSettings;
import io.github.ricardoord.opswatch.egress.TargetKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import org.apache.hc.client5.http.ClientProtocolException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.MessageConstraintException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The clients of {@code egress} against a simulated target on {@code 127.0.0.1}, which the tests reach because they
 * open {@code 127.0.0.0/8}: the use that {@code allowed-private-cidrs} exists for. Names resolve through a
 * {@link FakeHostResolver}, never the real DNS.
 */
class EgressHttpClientsTest {

    private static final String HOST = "target.example.com";
    private static final String USER_AGENT = "OpsWatch-Test/0";

    @RegisterExtension
    static WireMockExtension target = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"))
            .build();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FakeHostResolver names = new FakeHostResolver().with(HOST, "127.0.0.1");
    private final CloseableHttpClient client = client(List.of("127.0.0.0/8"), Duration.ofSeconds(2));

    @AfterEach
    void closeTheClient() throws IOException {
        client.close();
    }

    @Test
    void reachesAnAllowedTargetThroughTheGuardedResolver() throws IOException {
        target.stubFor(get("/health").willReturn(ok()));

        assertThat(status(client, url("/health"))).isEqualTo(200);
        assertThat(names.lookups()).containsExactly(HOST);
        target.verify(getRequestedFor(urlEqualTo("/health")).withHeader("User-Agent", equalTo(USER_AGENT)));
    }

    /** Case 16 and 17 inside the client: the addresses it connects to are the ones the resolver validated. */
    @Test
    void blocksANameThatResolvesToABlockedAddressWhenConnecting() {
        names.with("mixed.example.com", "93.184.216.34", "10.0.0.5");
        names.with("rebind.example.com", "93.184.216.34");
        DefaultTargetPolicy policy = new DefaultTargetPolicy(names, properties(List.of("127.0.0.0/8")));
        policy.validate("http://rebind.example.com/", TargetKind.MONITOR);
        names.with("rebind.example.com", "10.0.0.5");

        for (String host : List.of("mixed.example.com", "rebind.example.com")) {
            assertThatThrownBy(() -> status(client, "http://" + host + ":" + target.getPort() + "/health"))
                    .isInstanceOf(BlockedTargetException.class)
                    .hasMessageContaining(host);
        }
        target.verify(0, anyRequestedFor(anyUrl()));
        assertThat(blocked(BlockedTargets.Reason.ADDRESS)).isEqualTo(2);
    }

    /** If a version of the client ever skipped the resolver for literals, this would no longer be blocked. */
    @Test
    void anAddressLiteralGoesThroughTheGuardedResolverToo() throws IOException {
        target.stubFor(get("/health").willReturn(ok()));
        String literal = "http://127.0.0.1:" + target.getPort() + "/health";

        try (CloseableHttpClient strict = client(List.of(), Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> status(strict, literal))
                    .isInstanceOf(BlockedTargetException.class)
                    .extracting(ex -> ((BlockedTargetException) ex).rule())
                    .isEqualTo(GuardedDnsResolver.BLOCKED_ADDRESS);
        }
        assertThat(status(client, literal)).isEqualTo(200);
    }

    /** Layer 1 again on every request, without DNS: a URL saved before a rule existed never leaves. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://target.example.com:22/",
                "http://target.example.com:1023/",
                "http://localhost/",
                "http://intranet/",
                "http://2130706433/"
            })
    void checksTheUrlAgainBeforeEveryRequestWithoutResolvingIt(String url) {
        assertThatThrownBy(() -> status(client, url)).isInstanceOf(BlockedTargetException.class);

        assertThat(names.lookups()).isEmpty();
        target.verify(0, anyRequestedFor(anyUrl()));
        assertThat(blocked(BlockedTargets.Reason.URL)).isOne();
    }

    /** These the client itself refuses, before its execution chain: they do not leave either, and resolve nothing. */
    @ParameterizedTest
    @ValueSource(strings = {"ftp://target.example.com/", "http://user:secret@target.example.com/"})
    void neverSendsAnotherSchemeOrCredentialsInTheUrl(String url) {
        assertThatThrownBy(() -> status(client, url)).isInstanceOf(ClientProtocolException.class);

        assertThat(names.lookups()).isEmpty();
        target.verify(0, anyRequestedFor(anyUrl()));
    }

    /** Layer 4 again on every request: a header written to the database outside the API never leaves. */
    @Test
    void checksTheHeadersAgainBeforeEveryRequest() {
        for (String[] header : List.of(
                new String[] {"Metadata-Flavor", "Google"},
                new String[] {"Authorization", "Bearer Oracle"},
                new String[] {"X-Forwarded-For", "127.0.0.1"})) {
            HttpGet request = new HttpGet(url("/health"));
            request.addHeader(header[0], header[1]);

            assertThatThrownBy(() -> client.execute(request, response -> response.getCode()))
                    .as(header[0])
                    .isInstanceOf(BlockedTargetException.class)
                    .hasMessageNotContaining("Oracle")
                    .hasMessageNotContaining("Google");
        }
        target.verify(0, anyRequestedFor(anyUrl()));
        assertThat(blocked(BlockedTargets.Reason.HEADER)).isEqualTo(3);
    }

    @Test
    void ignoresTheProxyOfTheEnvironment() throws IOException {
        target.stubFor(get("/health").willReturn(ok()));
        String previousHost = System.setProperty("http.proxyHost", "127.0.0.2");
        String previousPort = System.setProperty("http.proxyPort", "9");
        try (CloseableHttpClient fresh = client(List.of("127.0.0.0/8"), Duration.ofSeconds(2))) {
            assertThat(status(fresh, url("/health"))).isEqualTo(200);
        } finally {
            restore("http.proxyHost", previousHost);
            restore("http.proxyPort", previousPort);
        }
    }

    /** A retry would hide an intermittent failure and distort the latency. */
    @Test
    void neverRetries() {
        target.stubFor(get("/health").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> status(client, url("/health"))).isInstanceOf(IOException.class);
        target.verify(1, getRequestedFor(urlEqualTo("/health")));
    }

    /** The caller follows redirects itself, so that every hop is checked again. */
    @Test
    void neverFollowsARedirect() throws IOException {
        target.stubFor(get("/health").willReturn(aResponse().withStatus(302).withHeader("Location", "/elsewhere")));

        assertThat(status(client, url("/health"))).isEqualTo(302);
        target.verify(0, getRequestedFor(urlEqualTo("/elsewhere")));
    }

    @Test
    void keepsNoCookiesAndAsksForNoCompression() throws IOException {
        target.stubFor(get("/login").willReturn(ok().withHeader("Set-Cookie", "session=abc; Path=/")));
        target.stubFor(get("/health").willReturn(ok()));

        status(client, url("/login"));
        status(client, url("/health"));

        target.verify(
                getRequestedFor(urlEqualTo("/health")).withoutHeader("Cookie").withoutHeader("Accept-Encoding"));
    }

    /** Case 23: a hostile target cannot exhaust the memory with headers. */
    @Test
    void refusesTooManyOrTooLongResponseHeaders() {
        ResponseDefinitionBuilder many = ok();
        for (int i = 0; i <= DefaultEgressHttpClients.MAX_RESPONSE_HEADERS; i++) {
            many = many.withHeader("X-Header-" + i, "x");
        }
        target.stubFor(get("/many").willReturn(many));
        target.stubFor(get("/long")
                .willReturn(ok().withHeader("X-Long", "x".repeat(DefaultEgressHttpClients.MAX_RESPONSE_HEADER_LINE))));

        assertThatThrownBy(() -> status(client, url("/many"))).isInstanceOf(MessageConstraintException.class);
        assertThatThrownBy(() -> status(client, url("/long"))).isInstanceOf(MessageConstraintException.class);
    }

    /** A target that never answers lets go after the timeout; the deadline over a whole check is OW-025's. */
    @Test
    void aSilentTargetTimesOut() throws IOException {
        target.stubFor(get("/slow").willReturn(ok().withFixedDelay(3000)));
        long start = System.nanoTime();

        try (CloseableHttpClient impatient = client(List.of("127.0.0.0/8"), Duration.ofMillis(300))) {
            assertThatThrownBy(() -> status(impatient, url("/slow"))).isInstanceOf(SocketTimeoutException.class);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500));
    }

    private CloseableHttpClient client(List<String> allowedPrivate, Duration timeout) {
        return new DefaultEgressHttpClients(names, properties(allowedPrivate), meters)
                .create(new EgressClientSettings(TargetKind.MONITOR, 10, timeout, USER_AGENT));
    }

    private double blocked(BlockedTargets.Reason reason) {
        return meters.counter(BlockedTargets.METRIC, "reason", reason.name()).count();
    }

    private static EgressProperties properties(List<String> allowedPrivate) {
        return new EgressProperties(allowedPrivate, Duration.ofSeconds(2));
    }

    private static String url(String path) {
        return "http://" + HOST + ":" + target.getPort() + path;
    }

    private static int status(CloseableHttpClient client, String url) throws IOException {
        return client.execute(new HttpGet(url), response -> response.getCode());
    }

    private static void restore(String property, String previous) {
        if (previous == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previous);
        }
    }
}
