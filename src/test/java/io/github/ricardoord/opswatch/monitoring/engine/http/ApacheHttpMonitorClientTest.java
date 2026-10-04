package io.github.ricardoord.opswatch.monitoring.engine.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.headRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.request;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.ricardoord.opswatch.egress.RequestHeader;
import io.github.ricardoord.opswatch.egress.internal.FakeHostResolver;
import io.github.ricardoord.opswatch.egress.internal.TestEgressHttpClients;
import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.domain.ProbeMethod;
import io.github.ricardoord.opswatch.monitoring.engine.HttpObservation;
import io.github.ricardoord.opswatch.monitoring.engine.HttpObservation.Failure;
import io.github.ricardoord.opswatch.monitoring.engine.HttpObservation.Response;
import io.github.ricardoord.opswatch.monitoring.engine.MonitoringEngineProperties;
import io.github.ricardoord.opswatch.monitoring.engine.ProbeRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The client of the checks against simulated targets on {@code 127.0.0.1}, opened with {@code allowed-private-cidrs}
 * as the tests of the engine do. Names resolve through a {@link FakeHostResolver}, never the real DNS. Covers the table
 * of docs/architecture/monitoring-engine.md#8-clasificación-de-fallos with real failures, and cases 18, 19, 22 and 23
 * of docs/security/ssrf-protection.md#5-casos-de-prueba-obligatorios.
 */
class ApacheHttpMonitorClientTest {

    private static final String HOST = "target.example.com";
    private static final String OTHER_HOST = "other.example.com";
    private static final String USER_AGENT = "OpsWatch-Test/0";
    private static final RequestHeader AUTHORIZATION = new RequestHeader("Authorization", "Bearer secret-token");

    @RegisterExtension
    static WireMockExtension target = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().dynamicHttpsPort().bindAddress("127.0.0.1"))
            .build();

    private final FakeHostResolver names =
            new FakeHostResolver().with(HOST, "127.0.0.1").with(OTHER_HOST, "127.0.0.1");
    private final ApacheHttpMonitorClient client = new ApacheHttpMonitorClient(
            TestEgressHttpClients.resolvingWith(names, "127.0.0.0/8"),
            new MonitoringEngineProperties(10, 5, Duration.ofMillis(200), USER_AGENT));

    @AfterEach
    void closeTheClient() throws IOException {
        client.close();
    }

    @Test
    void observesTheStatusAndTheTimeUpToTheHeaders() {
        target.stubFor(get("/health").willReturn(aResponse().withStatus(503).withFixedDelay(200)));

        Response response = response(client.probe(probe(url("/health"))));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.responseTime()).isBetween(Duration.ofMillis(200), Duration.ofMillis(1500));
        assertThat(response.redirectsFollowed()).isZero();
    }

    @Test
    void sendsTheHeadersOfTheMonitorAndTheUserAgent() {
        target.stubFor(get("/health").willReturn(ok()));

        response(client.probe(probe(url("/health"), AUTHORIZATION)));

        target.verify(getRequestedFor(urlEqualTo("/health"))
                .withHeader("Authorization", equalTo(AUTHORIZATION.value()))
                .withHeader("User-Agent", equalTo(USER_AGENT)));
    }

    /** The latency stops at the headers, and a huge or endless body costs nothing. */
    @Test
    void neverReadsTheBody() {
        target.stubFor(
                get("/large").willReturn(ok().withBody("x".repeat(1024 * 1024)).withChunkedDribbleDelay(50, 5000)));

        Response response = response(client.probe(probe(url("/large"))));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.responseTime()).isLessThan(Duration.ofMillis(1000));
    }

    @Test
    void aTargetSlowerThanTheTimeoutIsATimeout() {
        target.stubFor(get("/slow").willReturn(ok().withFixedDelay(3000)));

        Failure failure = failure(client.probe(probe(url("/slow"), Duration.ofMillis(500))));

        assertThat(failure.reason()).isEqualTo(FailureReason.TIMEOUT);
        assertThat(failure.detail()).isEqualTo(Failures.NO_RESPONSE_IN_TIME);
        assertThat(failure.elapsed()).isBetween(Duration.ofMillis(500), Duration.ofMillis(1000));
    }

    /** Case 22: no timeout of a read stops a target that keeps sending; the deadline does. */
    @Test
    void aTargetThatDripsItsHeadersIsCutByTheDeadline() throws IOException {
        try (DrippingTarget dripping = new DrippingTarget()) {
            Failure failure = failure(
                    client.probe(probe("http://" + HOST + ":" + dripping.port() + "/health", Duration.ofMillis(1000))));

            assertThat(failure.reason()).isEqualTo(FailureReason.TIMEOUT);
            assertThat(failure.elapsed()).isBetween(Duration.ofMillis(1000), Duration.ofMillis(1500));
        }
    }

    @Test
    void followsARedirectWithANewRequestThatResolvesAgain() {
        target.stubFor(get("/start").willReturn(redirect(302, "/next")));
        target.stubFor(get("/next").willReturn(ok()));

        Response response = response(client.probe(probe(url("/start"), AUTHORIZATION)));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.redirectsFollowed()).isEqualTo(1);
        assertThat(names.lookups()).containsExactly(HOST, HOST);
        target.verify(getRequestedFor(urlEqualTo("/next")).withHeader("Authorization", equalTo(AUTHORIZATION.value())));
    }

    /** Case 19: a redirect does not hand a credential to another host. */
    @Test
    void sendsTheHeadersOfTheMonitorOnlyToItsOrigin() {
        target.stubFor(
                get("/start").willReturn(redirect(302, "http://" + OTHER_HOST + ":" + target.getPort() + "/next")));
        target.stubFor(get("/next").willReturn(ok()));

        response(client.probe(probe(url("/start"), AUTHORIZATION)));

        target.verify(getRequestedFor(urlEqualTo("/next"))
                .withHeader("Host", equalTo(OTHER_HOST + ":" + target.getPort()))
                .withoutHeader("Authorization")
                .withHeader("User-Agent", equalTo(USER_AGENT)));
    }

    @ParameterizedTest
    @CsvSource({"301, GET", "302, HEAD", "303, GET", "303, HEAD", "307, HEAD", "308, GET"})
    void neverChangesTheMethodOfACheck(int statusCode, ProbeMethod method) {
        target.stubFor(request(method.name(), urlEqualTo("/start")).willReturn(redirect(statusCode, "/next")));
        target.stubFor(request(method.name(), urlEqualTo("/next")).willReturn(ok()));

        Response response = response(client.probe(
                new ProbeRequest(URI.create(url("/start")), method, List.of(), Duration.ofSeconds(2), true)));

        assertThat(response.statusCode()).isEqualTo(200);
        target.verify(
                method == ProbeMethod.HEAD
                        ? headRequestedFor(urlEqualTo("/next"))
                        : getRequestedFor(urlEqualTo("/next")));
    }

    @Test
    void followsUpToFiveRedirects() {
        for (int i = 0; i < 5; i++) {
            target.stubFor(get("/hop" + i).willReturn(redirect(302, "/hop" + (i + 1))));
        }
        target.stubFor(get("/hop5").willReturn(ok()));

        assertThat(response(client.probe(probe(url("/hop0")))).redirectsFollowed())
                .isEqualTo(5);
    }

    @Test
    void aSixthRedirectIsTooMany() {
        for (int i = 0; i < 6; i++) {
            target.stubFor(get("/hop" + i).willReturn(redirect(302, "/hop" + (i + 1))));
        }

        Failure failure = failure(client.probe(probe(url("/hop0"))));

        assertThat(failure.reason()).isEqualTo(FailureReason.TOO_MANY_REDIRECTS);
        assertThat(failure.detail()).isEqualTo(Failures.TOO_MANY_REDIRECTS);
        target.verify(0, getRequestedFor(urlEqualTo("/hop6")));
    }

    @Test
    void aLoopIsTooManyRedirectsWithoutWaitingForTheLimit() {
        target.stubFor(get("/a").willReturn(redirect(302, "/b")));
        target.stubFor(get("/b").willReturn(redirect(307, "/a#again")));

        Failure failure = failure(client.probe(probe(url("/a"))));

        assertThat(failure.reason()).isEqualTo(FailureReason.TOO_MANY_REDIRECTS);
        assertThat(failure.detail()).isEqualTo(Failures.REDIRECT_LOOP);
        target.verify(1, getRequestedFor(urlEqualTo("/a")));
    }

    /** The user may expect a 301. */
    @Test
    void withoutFollowingRedirectsA3xxIsTheResponse() {
        target.stubFor(get("/start").willReturn(redirect(301, "/next")));

        Response response = response(client.probe(
                new ProbeRequest(URI.create(url("/start")), ProbeMethod.GET, List.of(), Duration.ofSeconds(2), false)));

        assertThat(response.statusCode()).isEqualTo(301);
        assertThat(response.redirectsFollowed()).isZero();
        target.verify(0, getRequestedFor(urlEqualTo("/next")));
    }

    @Test
    void aRedirectWithoutAValidLocationIsAProtocolError() {
        target.stubFor(get("/none").willReturn(aResponse().withStatus(302)));
        target.stubFor(get("/malformed").willReturn(redirect(302, "http://exa mple.com/")));

        for (String path : List.of("/none", "/malformed")) {
            Failure failure = failure(client.probe(probe(url(path))));

            assertThat(failure.reason()).as(path).isEqualTo(FailureReason.PROTOCOL_ERROR);
            assertThat(failure.detail()).as(path).isEqualTo(Failures.INVALID_LOCATION);
        }
    }

    /**
     * Case 18 and its relatives: every hop goes through the checks of egress again, and what the client refuses itself
     * is blocked as well, not a protocol error of the target.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://169.254.169.254/latest/meta-data/",
                "http://10.0.0.5/",
                "http://target.example.com:22/",
                "http://localhost/",
                "ftp://target.example.com/",
                "file:///etc/passwd",
                "http://user:secret@target.example.com/"
            })
    void aRedirectThatTheSsrfProtectionStopsIsBlocked(String location) {
        target.stubFor(get("/start").willReturn(redirect(302, location)));

        Failure failure = failure(client.probe(probe(url("/start"))));

        assertThat(failure.reason()).isEqualTo(FailureReason.TARGET_BLOCKED);
        assertThat(failure.detail()).isEqualTo(Failures.BLOCKED);
        target.verify(1, anyRequestedFor(anyUrl()));
    }

    /** A URL or a header written to the database outside the API, or saved before a rule existed. */
    @Test
    void aMonitorThatTheSsrfProtectionStopsIsBlocked() {
        names.with("internal.example.com", "10.0.0.5");

        List<HttpObservation> observations = List.of(
                client.probe(probe("http://internal.example.com/")),
                client.probe(probe("ftp://" + HOST + "/")),
                client.probe(probe("http://user:secret@" + HOST + ":" + target.getPort() + "/")),
                client.probe(probe(url("/health"), new RequestHeader("Metadata-Flavor", "Google"))));

        assertThat(observations)
                .allSatisfy(observation ->
                        assertThat(failure(observation).reason()).isEqualTo(FailureReason.TARGET_BLOCKED));
        target.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void aNameThatDoesNotResolveIsADnsFailure() {
        Failure failure = failure(client.probe(probe("http://nowhere.example.com/")));

        assertThat(failure.reason()).isEqualTo(FailureReason.DNS_FAILURE);
        assertThat(failure.detail()).isEqualTo(Failures.HOST_NOT_FOUND);
    }

    @Test
    void aRefusedOrResetConnectionFails() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        target.stubFor(get("/reset").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        Failure refused = failure(client.probe(probe("http://" + HOST + ":" + closedPort + "/")));
        Failure reset = failure(client.probe(probe(url("/reset"))));

        assertThat(refused.reason()).isEqualTo(FailureReason.CONNECTION_FAILED);
        assertThat(refused.detail()).isEqualTo(Failures.COULD_NOT_CONNECT);
        assertThat(reset.reason()).isEqualTo(FailureReason.CONNECTION_FAILED);
    }

    @Test
    void aSelfSignedCertificateIsATlsFailure() {
        target.stubFor(get("/health").willReturn(ok()));

        Failure failure = failure(client.probe(probe("https://" + HOST + ":" + target.getHttpsPort() + "/health")));

        assertThat(failure.reason()).isEqualTo(FailureReason.TLS_FAILURE);
        assertThat(failure.detail()).isEqualTo(Failures.CERTIFICATE_NOT_TRUSTED);
    }

    /** Case 23, and a target that answers no HTTP at all. */
    @Test
    void aResponseOutsideTheProtocolOrItsLimitsIsAProtocolError() {
        ResponseDefinitionBuilder many = ok();
        for (int i = 0; i < 150; i++) {
            many = many.withHeader("X-Header-" + i, "x");
        }
        target.stubFor(get("/many").willReturn(many));
        target.stubFor(get("/empty").willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        Failure tooMany = failure(client.probe(probe(url("/many"))));
        Failure empty = failure(client.probe(probe(url("/empty"))));

        assertThat(tooMany.reason()).isEqualTo(FailureReason.PROTOCOL_ERROR);
        assertThat(tooMany.detail()).isEqualTo(Failures.HEADERS_OVER_LIMIT);
        assertThat(empty.reason()).isEqualTo(FailureReason.PROTOCOL_ERROR);
        assertThat(empty.detail()).isEqualTo(Failures.NO_HTTP_RESPONSE);
    }

    @Test
    void aHeadCheckGetsTheSameObservation() {
        target.stubFor(head(urlEqualTo("/health")).willReturn(aResponse().withStatus(204)));

        Response response = response(client.probe(new ProbeRequest(
                URI.create(url("/health")), ProbeMethod.HEAD, List.of(), Duration.ofSeconds(2), true)));

        assertThat(response.statusCode()).isEqualTo(204);
    }

    private static ProbeRequest probe(String url, RequestHeader... headers) {
        return new ProbeRequest(URI.create(url), ProbeMethod.GET, List.of(headers), Duration.ofSeconds(2), true);
    }

    private static ProbeRequest probe(String url, Duration timeout) {
        return new ProbeRequest(URI.create(url), ProbeMethod.GET, List.of(), timeout, true);
    }

    private static ResponseDefinitionBuilder redirect(int statusCode, String location) {
        return aResponse().withStatus(statusCode).withHeader("Location", location);
    }

    private static String url(String path) {
        return "http://" + HOST + ":" + target.getPort() + path;
    }

    private static Response response(HttpObservation observation) {
        assertThat(observation).isInstanceOf(Response.class);
        return (Response) observation;
    }

    private static Failure failure(HttpObservation observation) {
        assertThat(observation).isInstanceOf(Failure.class);
        return (Failure) observation;
    }

    /**
     * Case 22: the status line, then one byte of a header every 100 ms, for as long as the client listens. WireMock can
     * delay or dribble a body, but not the headers.
     */
    private static final class DrippingTarget implements AutoCloseable {

        private final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

        DrippingTarget() throws IOException {
            threads.submit(this::serve);
        }

        int port() {
            return server.getLocalPort();
        }

        private Void serve() throws InterruptedException {
            try (Socket socket = server.accept()) {
                socket.getInputStream().read(new byte[8192]);
                OutputStream out = socket.getOutputStream();
                out.write("HTTP/1.1 200 OK\r\nX-Drip: ".getBytes(US_ASCII));
                while (true) {
                    out.write('x');
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (IOException clientLetGo) {
                return null;
            }
        }

        @Override
        public void close() throws IOException {
            threads.shutdownNow();
            server.close();
        }
    }
}
