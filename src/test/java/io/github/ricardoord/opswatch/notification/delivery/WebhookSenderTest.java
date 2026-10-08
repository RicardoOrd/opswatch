package io.github.ricardoord.opswatch.notification.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.egress.EgressHttpClients;
import io.github.ricardoord.opswatch.egress.internal.FakeHostResolver;
import io.github.ricardoord.opswatch.egress.internal.TestCertificate;
import io.github.ricardoord.opswatch.egress.internal.TestEgressHttpClients;
import io.github.ricardoord.opswatch.incident.IncidentSummary;
import io.github.ricardoord.opswatch.incident.Resolution;
import io.github.ricardoord.opswatch.notification.application.ChannelConfig;
import io.github.ricardoord.opswatch.notification.application.DeliveryProperties;
import io.github.ricardoord.opswatch.notification.domain.DeliveryEventType;
import io.github.ricardoord.opswatch.shared.time.MutableClock;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The sender against WireMock on {@code 127.0.0.1} over {@code https}, with a certificate made for the test that only
 * this client trusts, its host name verified. {@code *Test} and not {@code *IT}: WireMock runs inside the process, as
 * in the tests of the HTTP client of the engine. Names resolve through a {@link FakeHostResolver}, never the real DNS.
 */
class WebhookSenderTest {

    private static final String HOST = "hooks.example.com";
    private static final String PRIVATE_HOST = "internal.example.com";
    private static final String USER_AGENT = "OpsWatch-Webhook/test";
    /** Low entropy on purpose: a real-looking secret in a test would trip the secrets scan. */
    private static final String SECRET = "whsec_" + "1".repeat(43);

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final TestCertificate CERTIFICATE = TestCertificate.forHosts(HOST);

    @RegisterExtension
    static WireMockExtension receiver = WireMockExtension.newInstance()
            .options(wireMockConfig()
                    .dynamicPort()
                    .dynamicHttpsPort()
                    .bindAddress("127.0.0.1")
                    .keystorePath(CERTIFICATE.keystorePath())
                    .keystorePassword(TestCertificate.PASSWORD)
                    .keyManagerPassword(TestCertificate.PASSWORD)
                    .keystoreType("PKCS12"))
            .build();

    private final FakeHostResolver names =
            new FakeHostResolver().with(HOST, "127.0.0.1").with(PRIVATE_HOST, "10.0.0.5");
    private final MutableClock clock = new MutableClock(NOW);
    private final WebhookSender sender =
            sender(TestEgressHttpClients.trusting(CERTIFICATE, names, "127.0.0.0/8"), Duration.ofSeconds(1));

    @AfterEach
    void close() throws IOException {
        sender.close();
    }

    /** Acceptance criterion of OW-043: an independent verifier validates the signature with the secret of the channel. */
    @Test
    void postsTheBodyOfTheCatalogSignedWithTheSecretOfTheChannel() {
        receiver.stubFor(post("/hooks").willReturn(ok()));
        Notice notice = opened();

        sender.send(notice, webhook("/hooks"));

        LoggedRequest request = onlyRequest();
        assertThat(request.getHeader("Content-Type")).startsWith("application/json");
        assertThat(request.getHeader("User-Agent")).isEqualTo(USER_AGENT);
        assertThat(request.getHeader(WebhookSigner.VERSION_HEADER)).isEqualTo("1");
        String signature = request.getHeader(WebhookSigner.SIGNATURE_HEADER);
        assertThat(signature).startsWith("t=" + NOW.getEpochSecond() + ",v1=");
        assertThat(WebhookSignatureVerifier.verify(SECRET, signature, request.getBody(), NOW, Duration.ofMinutes(5)))
                .isTrue();
        assertThat(WebhookSignatureVerifier.verify(
                        SECRET + "x", signature, request.getBody(), NOW, Duration.ofMinutes(5)))
                .isFalse();
        String body = new String(request.getBody(), StandardCharsets.UTF_8);
        IncidentSummary incident = notice.incident();
        assertThat(JsonPath.<Map<String, Object>>read(body, "$"))
                .containsEntry("id", notice.deliveryId().toString())
                .containsEntry("type", "INCIDENT_OPENED")
                .containsEntry("occurredAt", "2026-10-08T09:57:00Z");
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.incident"))
                .containsEntry("id", incident.id().toString())
                .containsEntry("status", "OPEN")
                .containsEntry("monitorId", incident.monitorId().toString())
                .containsEntry("monitorName", "Payments API")
                .containsEntry("projectId", incident.projectId().toString())
                .containsEntry("cause", "UNEXPECTED_STATUS")
                .containsEntry("httpStatus", 503)
                .containsEntry("openedAt", "2026-10-08T09:57:00Z")
                .doesNotContainKeys("resolvedAt", "resolution");
    }

    @Test
    void aResolutionSaysWhenAndHowAndATestHasNoIncident() {
        receiver.stubFor(post("/hooks").willReturn(ok()));
        Instant resolvedAt = NOW.minusSeconds(60);
        IncidentSummary resolved = incident("RESOLVED", resolvedAt, Resolution.AUTO_RECOVERED);

        sender.send(
                new Notice(UUID.randomUUID(), DeliveryEventType.INCIDENT_RESOLVED, NOW, "Bridge", resolved),
                webhook("/hooks"));
        sender.send(new Notice(UUID.randomUUID(), DeliveryEventType.TEST, NOW, "Bridge", null), webhook("/hooks"));

        List<String> bodies = receiver.findAll(postRequestedFor(urlEqualTo("/hooks"))).stream()
                .map(LoggedRequest::getBodyAsString)
                .toList();
        assertThat(JsonPath.<Map<String, Object>>read(bodies.get(0), "$"))
                .containsEntry("type", "INCIDENT_RESOLVED")
                .containsEntry("occurredAt", "2026-10-08T09:59:00Z");
        assertThat(JsonPath.<Map<String, Object>>read(bodies.get(0), "$.incident"))
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolvedAt", "2026-10-08T09:59:00Z")
                .containsEntry("resolution", "AUTO_RECOVERED");
        assertThat(JsonPath.<Map<String, Object>>read(bodies.get(1), "$"))
                .containsEntry("type", "TEST")
                .containsEntry("occurredAt", "2026-10-08T10:00:00Z")
                .containsEntry("incident", null);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 410, 500, 503})
    void anyAnswerOutside2xxIsAFailedAttempt(int status) {
        receiver.stubFor(post("/hooks").willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> sender.send(opened(), webhook("/hooks")))
                .isInstanceOfSatisfying(
                        DeliveryFailedException.class,
                        ex -> assertThat(ex.reason()).isEqualTo("receiver answered " + status));
    }

    @Test
    void anyAnswerIn2xxIsSent() {
        receiver.stubFor(post("/hooks").willReturn(aResponse().withStatus(204)));

        assertThatCode(() -> sender.send(opened(), webhook("/hooks"))).doesNotThrowAnyException();
    }

    /**
     * Acceptance criterion of OW-043: a {@code 3xx} is a failed attempt, also towards {@code http://} or a private
     * address, and its target receives nothing. The signed body never leaves for another URL.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://hooks.example.com:{port}/elsewhere",
                "http://hooks.example.com:{httpPort}/elsewhere",
                "https://169.254.169.254/latest/meta-data/",
                "https://internal.example.com/hooks"
            })
    void aRedirectIsAFailedAttemptAndItsTargetGetsNothing(String location) {
        receiver.stubFor(post("/hooks")
                .willReturn(aResponse()
                        .withStatus(307)
                        .withHeader(
                                "Location",
                                location.replace("{port}", String.valueOf(receiver.getHttpsPort()))
                                        .replace("{httpPort}", String.valueOf(receiver.getPort())))));
        receiver.stubFor(post("/elsewhere").willReturn(ok()));

        assertThatThrownBy(() -> sender.send(opened(), webhook("/hooks")))
                .isInstanceOfSatisfying(
                        DeliveryFailedException.class,
                        ex -> assertThat(ex.reason()).isEqualTo("receiver answered 307; redirects are not followed"));
        receiver.verify(1, anyRequestedFor(anyUrl()));
        receiver.verify(0, postRequestedFor(urlEqualTo("/elsewhere")));
    }

    /** Acceptance criterion of OW-043: a URL that resolves to a private address by now fails without sending the body. */
    @Test
    void aUrlThatNowResolvesToAPrivateAddressFailsWithoutSending() {
        ChannelConfig.Webhook rebound =
                new ChannelConfig.Webhook("https://" + PRIVATE_HOST + ":" + receiver.getHttpsPort() + "/hooks", SECRET);

        assertThatThrownBy(() -> sender.send(opened(), rebound))
                .isInstanceOfSatisfying(
                        DeliveryFailedException.class,
                        ex -> assertThat(ex.reason())
                                .isEqualTo("target not allowed")
                                .doesNotContain(PRIVATE_HOST));
        receiver.verify(0, anyRequestedFor(anyUrl()));
    }

    /** A URL written to the database outside the API, which the save would have rejected, never leaves either. */
    @Test
    void aUrlThatIsNotHttpsNeverLeaves() {
        receiver.stubFor(post("/hooks").willReturn(ok()));
        ChannelConfig.Webhook plain =
                new ChannelConfig.Webhook("http://" + HOST + ":" + receiver.getPort() + "/hooks", SECRET);

        assertThatThrownBy(() -> sender.send(opened(), plain))
                .isInstanceOfSatisfying(
                        DeliveryFailedException.class,
                        ex -> assertThat(ex.reason()).isEqualTo("target not allowed"));
        receiver.verify(0, anyRequestedFor(anyUrl()));
    }

    /** Acceptance criterion of OW-043: a receiver that takes longer than the timeout is a failed attempt. */
    @Test
    void aReceiverSlowerThanTheTimeoutIsAFailedAttempt() {
        receiver.stubFor(post("/hooks").willReturn(ok().withFixedDelay(3_000)));
        long start = System.nanoTime();

        assertThatThrownBy(() -> sender.send(opened(), webhook("/hooks")))
                .isInstanceOfSatisfying(
                        DeliveryFailedException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(WebhookSender.TIMED_OUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2_500));
    }

    /**
     * T-33: the deadline is over the whole send. A receiver that drips its headers, a byte every 100 ms, never trips the
     * timeout of a read, and only the deadline cuts it.
     */
    @Test
    // Without the deadline it would wait for the receiver forever, in a read that only another thread can give up on
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aReceiverThatDripsItsHeadersIsCutByTheDeadline() throws IOException {
        try (DrippingReceiver dripping = new DrippingReceiver()) {
            ChannelConfig.Webhook target =
                    new ChannelConfig.Webhook("https://" + HOST + ":" + dripping.port() + "/hooks", SECRET);
            long start = System.nanoTime();

            assertThatThrownBy(() -> sender.send(opened(), target))
                    .isInstanceOfSatisfying(
                            DeliveryFailedException.class,
                            ex -> assertThat(ex.reason()).isEqualTo(WebhookSender.TIMED_OUT));
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .isBetween(Duration.ofMillis(1_000), Duration.ofMillis(1_800));
        }
    }

    /** Only the status matters: a receiver that drips the body of its answer holds nothing up. */
    @Test
    void theBodyOfTheAnswerIsNeverRead() {
        receiver.stubFor(
                post("/hooks").willReturn(ok().withBody("x".repeat(100)).withChunkedDribbleDelay(100, 3_000)));
        long start = System.nanoTime();

        assertThatCode(() -> sender.send(opened(), webhook("/hooks"))).doesNotThrowAnyException();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2_500));
    }

    @Test
    void aCertificateTheJvmDoesNotTrustIsAFailedAttempt() throws IOException {
        receiver.stubFor(post("/hooks").willReturn(ok()));
        try (WebhookSender untrusting =
                sender(TestEgressHttpClients.resolvingWith(names, "127.0.0.0/8"), Duration.ofSeconds(1))) {

            assertThatThrownBy(() -> untrusting.send(opened(), webhook("/hooks")))
                    .isInstanceOfSatisfying(
                            DeliveryFailedException.class,
                            ex -> assertThat(ex.reason()).isEqualTo("TLS handshake failed"));
        }
        receiver.verify(0, anyRequestedFor(anyUrl()));
    }

    private WebhookSender sender(EgressHttpClients clients, Duration timeout) {
        return new WebhookSender(
                clients,
                new WebhookProperties(timeout, USER_AGENT),
                new DeliveryProperties(
                        true, Duration.ofSeconds(5), 10, 1, List.of(Duration.ZERO), Duration.ofMinutes(5)),
                clock);
    }

    private ChannelConfig.Webhook webhook(String path) {
        return new ChannelConfig.Webhook("https://" + HOST + ":" + receiver.getHttpsPort() + path, SECRET);
    }

    private static LoggedRequest onlyRequest() {
        List<LoggedRequest> requests = receiver.findAll(postRequestedFor(urlEqualTo("/hooks")));
        assertThat(requests).hasSize(1);
        return requests.getFirst();
    }

    private static Notice opened() {
        return new Notice(
                UUID.randomUUID(), DeliveryEventType.INCIDENT_OPENED, NOW, "Bridge", incident("OPEN", null, null));
    }

    private static IncidentSummary incident(
            String status, @Nullable Instant resolvedAt, @Nullable Resolution resolution) {
        return new IncidentSummary(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Payments API",
                status,
                "UNEXPECTED_STATUS",
                503,
                NOW.minusSeconds(180),
                resolvedAt,
                resolution);
    }

    /** The status line over TLS, then one byte of a header every 100 ms, for as long as the client listens. */
    private static final class DrippingReceiver implements AutoCloseable {

        private final ServerSocket server =
                CERTIFICATE.serve().getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress());
        private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

        DrippingReceiver() throws IOException {
            threads.submit(this::serve);
        }

        int port() {
            return server.getLocalPort();
        }

        private Void serve() throws InterruptedException {
            try (Socket socket = server.accept()) {
                socket.getInputStream().read(new byte[8192]);
                OutputStream out = socket.getOutputStream();
                out.write("HTTP/1.1 200 OK\r\nX-Drip: ".getBytes(StandardCharsets.US_ASCII));
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
