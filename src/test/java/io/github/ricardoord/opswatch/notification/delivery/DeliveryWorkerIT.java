package io.github.ricardoord.opswatch.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import io.github.ricardoord.opswatch.IntegrationTest;
import io.github.ricardoord.opswatch.TestHostResolver;
import io.github.ricardoord.opswatch.incident.IncidentDirectory;
import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.application.CheckResultRecorder;
import io.github.ricardoord.opswatch.monitoring.application.MonitorService;
import io.github.ricardoord.opswatch.monitoring.application.SettingsChanges;
import io.github.ricardoord.opswatch.monitoring.domain.CheckOutcome;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSnapshot;
import io.github.ricardoord.opswatch.notification.NotificationRows;
import io.github.ricardoord.opswatch.notification.application.ChannelConfigs;
import io.github.ricardoord.opswatch.notification.application.ChannelDestination;
import io.github.ricardoord.opswatch.notification.application.ChannelService;
import io.github.ricardoord.opswatch.notification.application.DeliveryProperties;
import io.github.ricardoord.opswatch.notification.domain.ChannelType;
import io.github.ricardoord.opswatch.notification.domain.ClaimedDelivery;
import io.github.ricardoord.opswatch.notification.domain.DeliveryEventType;
import io.github.ricardoord.opswatch.notification.domain.DeliveryQueue;
import io.github.ricardoord.opswatch.notification.domain.NotificationChannelRepository;
import io.github.ricardoord.opswatch.shared.time.MutableClock;
import io.github.ricardoord.opswatch.shared.web.PatchField;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The worker against PostgreSQL and GreenMail, which the tests stop to play an SMTP server that is down.
 *
 * <p>Built by hand, as the dispatcher of the engine in its tests, with a clock in 2001: the tests share the database,
 * and every other delivery is due at the real time, so a claim in 2001 sees only the deliveries made here. Each test
 * makes them due then, and {@link #clearThePast()} takes them out of the queue before and after each one.
 */
@IntegrationTest
class DeliveryWorkerIT {

    private static final Instant EPOCH = Instant.parse("2001-01-01T00:00:00Z");

    /** Every delivery made here is due before this, and nothing else is. */
    private static final Instant HORIZON = Instant.parse("2002-01-01T00:00:00Z");

    private static final DeliveryProperties PROPERTIES = new DeliveryProperties(
            true,
            Duration.ofSeconds(5),
            50,
            6,
            List.of(
                    Duration.ZERO,
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(2),
                    Duration.ofMinutes(10),
                    Duration.ofMinutes(30),
                    Duration.ofHours(1)),
            Duration.ofMinutes(5));

    private static final String HEALTH = "https://" + TestHostResolver.PUBLIC_HOST + "/health";

    /** A failure threshold of 3 and a recovery threshold of 2. */
    private static final SettingsChanges DEFAULT_SETTINGS =
            new SettingsChanges(null, null, null, null, null, PatchField.absent(), null, null, null);

    private static final CheckOutcome TIMED_OUT =
            CheckOutcome.down(FailureReason.TIMEOUT, null, null, "no response within the timeout");
    private static final CheckOutcome HEALTHY = CheckOutcome.up(200, Duration.ofMillis(143));

    /** The same on every start: the SMTP server goes down and comes back where the sender expects it. */
    private static final int SMTP_PORT = freePort();

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
    private MonitorService monitors;

    @Autowired
    private CheckResultRecorder recorder;

    @Autowired
    private TransactionOperations transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final GreenMail smtp = new GreenMail(new ServerSetup(SMTP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
    private final MutableClock clock = new MutableClock(EPOCH);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private DeliveryWorker worker;
    private NotificationRows rows;

    @BeforeEach
    void startTheWorker() {
        clearThePast();
        smtp.start();
        rows = new NotificationRows(jdbc);
        EmailSender sender = new EmailSender(
                mailSender(), new EmailTemplates(), new EmailProperties("OpsWatch <alerts@opswatch.test>"), clock);
        worker = new DeliveryWorker(
                queue, channels, configs, incidents, List.of(sender), PROPERTIES, transactions, meters, clock);
    }

    @AfterEach
    void stop() {
        smtp.stop();
        clearThePast();
    }

    /**
     * Acceptance criterion of OW-036, end to end: the real engine results open and resolve the incident, the listener
     * turns each into deliveries after the commit, and the worker sends exactly one email of each per channel.
     */
    @Test
    void aTenMinuteOutageSendsOneOpeningAndOneResolutionToEachChannel() throws Exception {
        UUID owner = rows.user();
        UUID organization = rows.organizationOwnedBy(owner);
        UUID project = rows.project(organization);
        MonitorSnapshot monitor =
                MonitorSnapshot.of(monitors.create(owner, project, "Payments API", HEALTH, DEFAULT_SETTINGS, List.of())
                        .monitor());
        String everyProject = "all-" + UUID.randomUUID() + "@example.com";
        String thisProject = "payments-" + UUID.randomUUID() + "@example.com";
        UUID first = emailChannel(owner, organization, null, everyProject);
        UUID second = emailChannel(owner, organization, project, thisProject);
        Instant start = Instant.now().truncatedTo(ChronoUnit.MICROS);

        for (int failure = 1; failure <= 3; failure++) {
            recorder.record(monitor, start.plusSeconds(failure), TIMED_OUT);
        }
        recorder.record(monitor, start.plus(Duration.ofMinutes(10)), HEALTHY);
        recorder.record(monitor, start.plus(Duration.ofMinutes(10)).plusSeconds(1), HEALTHY);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM notification_deliveries WHERE channel_id IN (?, ?)",
                                Long.class,
                                first,
                                second))
                        .isEqualTo(4));
        // Into the past of these tests, where only this worker claims them
        jdbc.update(
                "UPDATE notification_deliveries SET next_attempt_at = ? WHERE channel_id IN (?, ?)",
                NotificationRows.at(EPOCH),
                first,
                second);

        assertThat(worker.deliver(EPOCH)).isEqualTo(4);
        assertThat(worker.deliver(EPOCH.plus(Duration.ofDays(1)))).isZero();

        for (String recipient : List.of(everyProject, thisProject)) {
            assertThat(subjectsTo(recipient))
                    .containsExactlyInAnyOrder("[OpsWatch] DOWN: Payments API", "[OpsWatch] RESOLVED: Payments API");
        }
    }

    @Test
    void sendsADueDeliveryAndRecordsItSent() throws Exception {
        Fixture fixture = newFixture();
        UUID delivery = dueFor(fixture, DeliveryEventType.INCIDENT_OPENED);

        assertThat(worker.deliver(EPOCH)).isOne();

        MimeMessage[] received = smtp.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getSubject()).isEqualTo("[OpsWatch] DOWN: Payments API");
        assertThat(received[0].getHeader(EmailSender.DELIVERY_HEADER)).containsExactly(delivery.toString());
        assertThat(rows.delivery(delivery))
                .containsEntry("status", "SENT")
                .containsEntry("attempts", 1)
                .containsEntry("next_attempt_at", null)
                .containsEntry("last_error", null)
                .containsEntry("last_attempt_at", NotificationRows.stored(EPOCH))
                .containsEntry("sent_at", NotificationRows.stored(EPOCH));
        assertThat(count(DeliveryWorker.SENT)).isOne();
        assertThat(worker.deliver(EPOCH)).as("nothing left to send").isZero();
    }

    /** Acceptance criterion of OW-036: with the SMTP server down the delivery is retried, and sent when it is back. */
    @Test
    void retriesWithBackoffWhileTheSmtpServerIsDownAndSendsWhenItIsBack() {
        Fixture fixture = newFixture();
        UUID delivery = dueFor(fixture, DeliveryEventType.INCIDENT_OPENED);
        smtp.stop();

        worker.deliver(EPOCH);

        assertThat(rows.delivery(delivery))
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 1)
                .containsEntry("last_error", "SMTP server unreachable")
                .containsEntry("next_attempt_at", NotificationRows.stored(EPOCH.plusSeconds(30)));
        assertThat(worker.deliver(EPOCH.plusSeconds(29))).as("not due yet").isZero();

        clock.advance(Duration.ofSeconds(30));
        worker.deliver(clock.instant());
        assertThat(rows.delivery(delivery))
                .containsEntry("attempts", 2)
                .containsEntry(
                        "next_attempt_at",
                        NotificationRows.stored(clock.instant().plus(Duration.ofMinutes(2))));

        smtp.start();
        clock.advance(Duration.ofMinutes(2));
        worker.deliver(clock.instant());

        assertThat(rows.delivery(delivery))
                .containsEntry("status", "SENT")
                .containsEntry("attempts", 3)
                .containsEntry("last_error", null);
        assertThat(smtp.getReceivedMessages()).hasSize(1);
        assertThat(count(DeliveryWorker.RETRY)).isEqualTo(2);
        assertThat(count(DeliveryWorker.SENT)).isOne();
    }

    /** Acceptance criterion of OW-036: {@code FAILED} after 6 attempts, the last one after an hour. */
    @Test
    void failsAfterTheLastAttempt() {
        Fixture fixture = newFixture();
        UUID delivery = dueFor(fixture, DeliveryEventType.INCIDENT_OPENED);
        smtp.stop();

        worker.deliver(clock.instant());
        for (int attempt = 1; attempt < PROPERTIES.maxAttempts(); attempt++) {
            clock.advance(PROPERTIES.waitAfter(attempt));
            assertThat(worker.deliver(clock.instant())).isOne();
        }

        assertThat(rows.delivery(delivery))
                .containsEntry("status", "FAILED")
                .containsEntry("attempts", 6)
                .containsEntry("next_attempt_at", null)
                .containsEntry("last_error", "SMTP server unreachable");
        // 0 s + 30 s + 2 min + 10 min + 30 min + 1 h
        assertThat(clock.instant()).isEqualTo(EPOCH.plus(Duration.ofSeconds(6150)));
        assertThat(count(DeliveryWorker.RETRY)).isEqualTo(5);
        assertThat(count(DeliveryWorker.FAILED)).isOne();
        clock.advance(Duration.ofDays(1));
        assertThat(worker.deliver(clock.instant())).as("never again").isZero();
    }

    @Test
    void aDisabledChannelFailsItsDeliveryWithoutSending() {
        Fixture fixture = newFixture();
        UUID delivery = dueFor(fixture, DeliveryEventType.INCIDENT_OPENED);
        jdbc.update("UPDATE notification_channels SET enabled = false WHERE id = ?", fixture.channel());

        worker.deliver(EPOCH);

        assertThat(rows.delivery(delivery))
                .containsEntry("status", "FAILED")
                .containsEntry("attempts", 1)
                .containsEntry("last_error", "channel disabled");
        assertThat(smtp.getReceivedMessages()).isEmpty();
        assertThat(count(DeliveryWorker.FAILED)).isOne();
    }

    @Test
    void theResolutionTellsHowTheIncidentEnded() throws Exception {
        Fixture fixture = newFixture();
        jdbc.update("""
                UPDATE incidents SET status = 'RESOLVED', resolved_at = ?, resolution = 'AUTO_RECOVERED'
                WHERE id = ?""", NotificationRows.at(EPOCH.plusSeconds(600)), fixture.incident());
        dueFor(fixture, DeliveryEventType.INCIDENT_RESOLVED);

        worker.deliver(EPOCH);

        MimeMessage received = smtp.getReceivedMessages()[0];
        assertThat(received.getSubject()).isEqualTo("[OpsWatch] RESOLVED: Payments API");
    }

    @Test
    void aTestDeliveryIsSentAsAnyOther() throws Exception {
        Fixture fixture = newFixture();
        UUID delivery = UUID.randomUUID();
        assertThat(queue.addTest(delivery, fixture.channel(), EPOCH, EPOCH)).isTrue();

        worker.deliver(EPOCH);

        assertThat(smtp.getReceivedMessages()[0].getSubject()).isEqualTo("[OpsWatch] Test notification: On call");
        assertThat(rows.delivery(delivery)).containsEntry("status", "SENT");
    }

    /**
     * A worker claims only the types it has a sender for: one built with the email sender alone leaves the deliveries
     * of a webhook in the queue, untouched, for one that can send them.
     */
    @Test
    void theDeliveriesOfATypeWithoutASenderWaitInTheQueue() {
        Fixture fixture = newFixture();
        UUID webhook = channelService
                .create(
                        fixture.owner(),
                        fixture.organization(),
                        "Bridge",
                        null,
                        new ChannelDestination.Webhook("https://" + TestHostResolver.PUBLIC_HOST + "/hooks"))
                .channel()
                .id();
        UUID delivery = UUID.randomUUID();
        queue.addForIncident(delivery, webhook, fixture.incident(), DeliveryEventType.INCIDENT_OPENED, EPOCH, EPOCH);

        assertThat(worker.deliver(EPOCH)).isZero();

        assertThat(rows.delivery(delivery)).containsEntry("status", "PENDING").containsEntry("attempts", 0);
    }

    /**
     * A worker whose lease ran out, while another one took the delivery again, does not write over the newer attempt.
     */
    @Test
    void aResultAfterTheLeaseRanOutChangesNothing() {
        Fixture fixture = newFixture();
        UUID delivery = dueFor(fixture, DeliveryEventType.INCIDENT_OPENED);
        Instant leaseEnd = EPOCH.plus(PROPERTIES.lease());
        List<ClaimedDelivery> first =
                transactions.execute(tx -> queue.claim(10, EPOCH, leaseEnd, List.of(ChannelType.EMAIL)));
        assertThat(first)
                .singleElement()
                .satisfies(claimed -> assertThat(claimed.attempt()).isOne());
        List<ClaimedDelivery> meanwhile =
                transactions.execute(tx -> queue.claim(10, EPOCH.plusSeconds(1), leaseEnd, List.of(ChannelType.EMAIL)));
        assertThat(meanwhile).as("set aside while it is sent").isEmpty();

        List<ClaimedDelivery> second = transactions.execute(
                tx -> queue.claim(10, leaseEnd, leaseEnd.plusSeconds(300), List.of(ChannelType.EMAIL)));
        assertThat(second)
                .singleElement()
                .satisfies(claimed -> assertThat(claimed.attempt()).isEqualTo(2));

        assertThat(queue.recordSent(delivery, 1, leaseEnd)).isFalse();
        assertThat(queue.recordRetry(delivery, 1, "late", leaseEnd)).isFalse();
        assertThat(rows.delivery(delivery)).containsEntry("status", "PENDING").containsEntry("attempts", 2);
        assertThat(queue.recordSent(delivery, 2, leaseEnd)).isTrue();
        assertThat(rows.delivery(delivery)).containsEntry("status", "SENT");
    }

    /** {@code SKIP LOCKED}: a delivery that another worker is claiming is skipped, never waited for. */
    @Test
    void aDeliveryAnotherWorkerIsClaimingIsSkipped() throws Exception {
        Fixture fixture = newFixture();
        UUID delivery = dueFor(fixture, DeliveryEventType.INCIDENT_OPENED);

        List<ClaimedDelivery> claimed = whileLocked(
                "SELECT id FROM notification_deliveries WHERE id = ? FOR UPDATE",
                delivery,
                () -> transactions.execute(
                        tx -> queue.claim(10, EPOCH, EPOCH.plus(PROPERTIES.lease()), List.of(ChannelType.EMAIL))));

        assertThat(claimed).isEmpty();
        assertThat(rows.delivery(delivery)).containsEntry("attempts", 0);
    }

    /** {@code FOR UPDATE OF d}: a claim locks the deliveries, never their channel, so a change of it does not wait. */
    @Test
    void aClaimDoesNotHoldTheChannelOfItsDeliveries() throws Exception {
        Fixture fixture = newFixture();
        dueFor(fixture, DeliveryEventType.INCIDENT_OPENED);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> claiming = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                assertThat(queue.claim(10, EPOCH, EPOCH.plus(PROPERTIES.lease()), List.of(ChannelType.EMAIL)))
                        .hasSize(1);
                claimed.countDown();
                waitFor(release);
            }));
            try {
                assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
                Future<Integer> renaming = executor.submit(() -> jdbc.update(
                        "UPDATE notification_channels SET name = 'Renamed' WHERE id = ?", fixture.channel()));

                assertThat(renaming.get(5, TimeUnit.SECONDS)).isOne();
            } finally {
                release.countDown();
            }
            claiming.get(10, TimeUnit.SECONDS);
        }
    }

    /** Runs {@code work} while another transaction holds the row that {@code lockSql} locks. */
    private <T> T whileLocked(String lockSql, UUID id, Supplier<T> work) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> holding = executor.submit(() -> transactions.executeWithoutResult(tx -> {
                jdbc.queryForList(lockSql, id);
                locked.countDown();
                waitFor(release);
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                return executor.submit(work::get).get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                holding.get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Never released");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private UUID emailChannel(UUID owner, UUID organization, @Nullable UUID project, String recipient) {
        return channelService
                .create(owner, organization, "On call", project, new ChannelDestination.Email(List.of(recipient)))
                .channel()
                .id();
    }

    private List<String> subjectsTo(String recipient) throws MessagingException {
        List<String> subjects = new ArrayList<>();
        for (MimeMessage message : smtp.getReceivedMessagesForDomain(recipient)) {
            subjects.add(message.getSubject());
        }
        return subjects;
    }

    private void clearThePast() {
        jdbc.update("""
                UPDATE notification_deliveries SET status = 'FAILED', next_attempt_at = NULL
                WHERE status = 'PENDING' AND next_attempt_at < ?""", NotificationRows.at(HORIZON));
    }

    private double count(String result) {
        return meters.counter(DeliveryWorker.DELIVERIES, "channel_type", "EMAIL", "result", result)
                .count();
    }

    /** An email channel of every project with one recipient, and an open incident of one of its projects. */
    private Fixture newFixture() {
        UUID owner = rows.user();
        UUID organization = rows.organizationOwnedBy(owner);
        UUID project = rows.project(organization);
        UUID incident = rows.openIncident(organization, project, "Payments API", EPOCH);
        UUID channel = channelService
                .create(
                        owner,
                        organization,
                        "On call",
                        null,
                        new ChannelDestination.Email(List.of("oncall-" + UUID.randomUUID() + "@example.com")))
                .channel()
                .id();
        return new Fixture(owner, organization, incident, channel);
    }

    private JavaMailSenderImpl mailSender() {
        JavaMailSenderImpl mail = new JavaMailSenderImpl();
        mail.setHost("127.0.0.1");
        mail.setPort(SMTP_PORT);
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "10000");
        mail.setJavaMailProperties(properties);
        return mail;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private record Fixture(UUID owner, UUID organization, UUID incident, UUID channel) {}

    /** Due in 2001, as only the deliveries of these tests are. */
    private UUID dueFor(Fixture fixture, DeliveryEventType type) {
        UUID id = UUID.randomUUID();
        assertThat(queue.addForIncident(id, fixture.channel(), fixture.incident(), type, EPOCH, EPOCH))
                .isTrue();
        return id;
    }
}
