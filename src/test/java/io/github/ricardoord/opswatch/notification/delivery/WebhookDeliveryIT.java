package io.github.ricardoord.opswatch.notification.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestHostResolver;
import io.github.ricardoord.opswatch.egress.internal.FakeHostResolver;
import io.github.ricardoord.opswatch.egress.internal.TestCertificate;
import io.github.ricardoord.opswatch.egress.internal.TestEgressHttpClients;
import io.github.ricardoord.opswatch.incident.IncidentDirectory;
import io.github.ricardoord.opswatch.notification.NotificationRows;
import io.github.ricardoord.opswatch.notification.application.ChannelConfig;
import io.github.ricardoord.opswatch.notification.application.ChannelConfigs;
import io.github.ricardoord.opswatch.notification.application.ChannelDestination;
import io.github.ricardoord.opswatch.notification.application.ChannelService;
import io.github.ricardoord.opswatch.notification.application.ChannelView;
import io.github.ricardoord.opswatch.notification.application.DeliveryProperties;
import io.github.ricardoord.opswatch.notification.domain.DeliveryEventType;
import io.github.ricardoord.opswatch.notification.domain.DeliveryQueue;
import io.github.ricardoord.opswatch.notification.domain.NotificationChannelRepository;
import io.github.ricardoord.opswatch.shared.time.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

/**
 * A webhook channel created through {@link ChannelService}, sent by the worker with its configuration decrypted from
 * the database: the receiver verifies the signature with the secret that the creation showed once (OW-043).
 *
 * <p>The URL names {@link TestHostResolver#PUBLIC_HOST}, which the policy of the shared context accepts on saving. The
 * sender of the test resolves the same name to WireMock on {@code 127.0.0.1}, with a certificate made for it. Its worker
 * is built by hand and claims in 2001, as in {@link DeliveryWorkerIT}.
 */
@IntegrationTest
class WebhookDeliveryIT {

    private static final Instant EPOCH = Instant.parse("2001-01-01T00:00:00Z");
    private static final Instant HORIZON = Instant.parse("2002-01-01T00:00:00Z");
    private static final TestCertificate CERTIFICATE = TestCertificate.forHosts(TestHostResolver.PUBLIC_HOST);

    /** One attempt after another, a second apart: enough to reach the last one. */
    private static final DeliveryProperties PROPERTIES = new DeliveryProperties(
            true,
            Duration.ofSeconds(5),
            10,
            3,
            List.of(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)),
            Duration.ofMinutes(5));

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

    @Autowired
    private DeliveryQueue queue;

    @Autowired
    private NotificationChannelRepository channels;

    @Autowired
    private ChannelConfigs configs;

    @Autowired
    private ChannelService channelService;

    @Autowired
    private IncidentDirectory incidents;

    @Autowired
    private TransactionOperations transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final MutableClock clock = new MutableClock(EPOCH);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private WebhookSender sender;
    private DeliveryWorker worker;
    private NotificationRows rows;

    @BeforeEach
    void startTheWorker() {
        clearThePast();
        rows = new NotificationRows(jdbc);
        sender = new WebhookSender(
                TestEgressHttpClients.trusting(
                        CERTIFICATE,
                        new FakeHostResolver().with(TestHostResolver.PUBLIC_HOST, "127.0.0.1"),
                        "127.0.0.0/8"),
                new WebhookProperties(Duration.ofSeconds(2), "OpsWatch-Webhook/test"),
                PROPERTIES,
                clock);
        worker = new DeliveryWorker(
                queue, channels, configs, incidents, List.of(sender), PROPERTIES, transactions, meters, clock);
    }

    @AfterEach
    void stop() throws IOException {
        sender.close();
        clearThePast();
    }

    @Test
    void aWebhookChannelGetsItsNoticeSignedWithTheSecretShownAtCreation() {
        receiver.stubFor(post("/hooks").willReturn(ok()));
        Fixture fixture = newFixture();

        assertThat(worker.deliver(EPOCH)).isOne();

        List<LoggedRequest> received = receiver.findAll(postRequestedFor(urlEqualTo("/hooks")));
        assertThat(received).hasSize(1);
        LoggedRequest request = received.getFirst();
        assertThat(WebhookSignatureVerifier.verify(
                        fixture.secret(),
                        request.getHeader(WebhookSigner.SIGNATURE_HEADER),
                        request.getBody(),
                        EPOCH,
                        Duration.ofMinutes(5)))
                .isTrue();
        assertThat(JsonPath.<String>read(request.getBodyAsString(), "$.id"))
                .isEqualTo(fixture.delivery().toString());
        assertThat(JsonPath.<String>read(request.getBodyAsString(), "$.incident.monitorName"))
                .isEqualTo("Payments API");
        assertThat(rows.delivery(fixture.delivery()))
                .containsEntry("status", "SENT")
                .containsEntry("attempts", 1);
        assertThat(meters.counter(DeliveryWorker.DELIVERIES, "channel_type", "WEBHOOK", "result", DeliveryWorker.SENT)
                        .count())
                .isOne();
    }

    /** A receiver that redirects is retried with backoff, and given up on after the last attempt. */
    @Test
    void aReceiverThatRedirectsIsRetriedAndThenGivenUp() {
        receiver.stubFor(post("/hooks")
                .willReturn(aResponse().withStatus(302).withHeader("Location", "https://example.org/elsewhere")));
        Fixture fixture = newFixture();

        worker.deliver(clock.instant());
        for (int attempt = 1; attempt < PROPERTIES.maxAttempts(); attempt++) {
            clock.advance(PROPERTIES.waitAfter(attempt));
            worker.deliver(clock.instant());
        }

        assertThat(rows.delivery(fixture.delivery()))
                .containsEntry("status", "FAILED")
                .containsEntry("attempts", 3)
                .containsEntry("last_error", "receiver answered 302; redirects are not followed");
        assertThat(receiver.findAll(postRequestedFor(urlEqualTo("/hooks")))).hasSize(3);
    }

    /** A webhook channel of every project, created through the service, and a delivery of an open incident due in 2001. */
    private Fixture newFixture() {
        UUID owner = rows.user();
        UUID organization = rows.organizationOwnedBy(owner);
        UUID project = rows.project(organization);
        UUID incident = rows.openIncident(organization, project, "Payments API", EPOCH);
        ChannelView created = channelService.create(
                owner,
                organization,
                "Bridge",
                null,
                new ChannelDestination.Webhook(
                        "https://" + TestHostResolver.PUBLIC_HOST + ":" + receiver.getHttpsPort() + "/hooks"));
        UUID delivery = UUID.randomUUID();
        queue.addForIncident(
                delivery, created.channel().id(), incident, DeliveryEventType.INCIDENT_OPENED, EPOCH, EPOCH);
        String secret = ((ChannelConfig.Webhook) created.config()).signingSecret();
        return new Fixture(delivery, secret);
    }

    private void clearThePast() {
        jdbc.update("""
                UPDATE notification_deliveries SET status = 'FAILED', next_attempt_at = NULL
                WHERE status = 'PENDING' AND next_attempt_at < ?""", NotificationRows.at(HORIZON));
    }

    /** @param secret as the response that created the channel showed it */
    private record Fixture(UUID delivery, String secret) {}
}
