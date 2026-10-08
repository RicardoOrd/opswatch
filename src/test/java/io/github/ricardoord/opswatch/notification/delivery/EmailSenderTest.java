package io.github.ricardoord.opswatch.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.ricardoord.opswatch.incident.IncidentSummary;
import io.github.ricardoord.opswatch.notification.application.ChannelConfig;
import io.github.ricardoord.opswatch.notification.domain.DeliveryEventType;
import io.github.ricardoord.opswatch.shared.time.MutableClock;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * The sender against GreenMail, an SMTP server inside the process: {@code *Test}, as the HTTP client against WireMock.
 */
class EmailSenderTest {

    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final ChannelConfig.Email RECIPIENTS =
            new ChannelConfig.Email(List.of("oncall@example.com", "sre@example.com"));

    /** Built once GreenMail is up, before each test: it gives the port. */
    private EmailSender sender;

    @BeforeEach
    void startSending() {
        sender = new EmailSender(
                mailSender(SMTP.getSmtp().getPort()),
                new EmailTemplates(),
                new EmailProperties("OpsWatch <alerts@opswatch.test>"),
                new MutableClock(NOW));
    }

    @Test
    void sendsOneMessageInTextAndHtmlToEveryRecipient() throws Exception {
        Notice notice = opened("Payments API");

        sender.send(notice, RECIPIENTS);

        MimeMessage[] received = SMTP.getReceivedMessages();
        // GreenMail keeps one copy per recipient
        assertThat(received).hasSize(2);
        MimeMessage message = received[0];
        assertThat(addresses(message.getFrom())).containsExactly("alerts@opswatch.test");
        assertThat(addresses(message.getAllRecipients()))
                .containsExactlyInAnyOrder("oncall@example.com", "sre@example.com");
        assertThat(message.getSubject()).isEqualTo("[OpsWatch] DOWN: Payments API");
        assertThat(message.getHeader(EmailSender.DELIVERY_HEADER))
                .containsExactly(notice.deliveryId().toString());
        assertThat(message.getHeader("Auto-Submitted")).containsExactly("auto-generated");
        assertThat(message.getSentDate()).isEqualTo(java.util.Date.from(NOW));
        Map<String, String> parts = textParts(message);
        assertThat(parts.get("text/plain")).contains("DOWN: Payments API");
        assertThat(parts.get("text/html")).contains("<strong>Payments API</strong>");
    }

    /** T-34: what a user typed never adds a header nor a recipient, and never reaches the HTML unescaped. */
    @Test
    void aMonitorNameCannotInjectHeadersOrHtml() throws Exception {
        sender.send(opened("<b>Payments</b>\r\nBcc: victim@example.com"), RECIPIENTS);

        MimeMessage message = SMTP.getReceivedMessages()[0];
        assertThat(message.getHeader("Subject")).hasSize(1);
        assertThat(message.getSubject()).isEqualTo("[OpsWatch] DOWN: <b>Payments</b> Bcc: victim@example.com");
        assertThat(message.getHeader("Bcc")).isNull();
        assertThat(addresses(message.getAllRecipients())).doesNotContain("victim@example.com");
        assertThat(SMTP.getReceivedMessages()).hasSize(2);
        assertThat(textParts(message).get("text/html"))
                .doesNotContain("<b>Payments</b>")
                .contains("&lt;b&gt;Payments&lt;/b&gt;");
    }

    @Test
    void anSmtpServerThatIsDownFailsTheAttemptWithoutNamingTheRecipients() {
        JavaMailSenderImpl closed = mailSender(SMTP.getSmtp().getPort());
        SMTP.stop();
        try {
            EmailSender down = new EmailSender(
                    closed, new EmailTemplates(), new EmailProperties("alerts@opswatch.test"), new MutableClock(NOW));

            assertThatThrownBy(() -> down.send(opened("Payments API"), RECIPIENTS))
                    .isInstanceOfSatisfying(
                            DeliveryFailedException.class,
                            ex -> assertThat(ex.reason()).isEqualTo("SMTP server unreachable"));
        } finally {
            SMTP.start();
        }
    }

    @Test
    void theReasonOfAFailureSaysWhatWentWrongWithTheServer() {
        assertThat(EmailSender.reasonOf(new MailAuthenticationException("535 5.7.8 bad credentials")))
                .isEqualTo("SMTP authentication failed");
        assertThat(EmailSender.reasonOf(new MailSendException(
                        "Mail server connection failed",
                        new MessagingException("connect", new AuthenticationFailedException("535")))))
                .isEqualTo("SMTP authentication failed");
        assertThat(EmailSender.reasonOf(new MailSendException(
                        "Mail server connection failed",
                        new MessagingException("read", new SocketTimeoutException("Read timed out")))))
                .isEqualTo("SMTP server timed out");
        assertThat(EmailSender.reasonOf(new MailSendException("Something else")))
                .isEqualTo("SMTP send failed");
    }

    /** The API shows the reason, and shows the recipients only masked. */
    @Test
    void aRejectedRecipientIsNeverNamedInTheReason() throws Exception {
        Address rejected = new InternetAddress("oncall@example.com");
        SendFailedException failure = new SendFailedException(
                "550 5.1.1 <oncall@example.com>: unknown user", null, null, null, new Address[] {rejected});
        Map<Object, Exception> failed = new HashMap<>();
        failed.put("message", failure);

        String reason = EmailSender.reasonOf(new MailSendException(failed));

        assertThat(reason)
                .isEqualTo("SMTP server rejected the message or a recipient")
                .doesNotContain("oncall");
    }

    private static Notice opened(String monitorName) {
        return new Notice(
                UUID.randomUUID(),
                DeliveryEventType.INCIDENT_OPENED,
                NOW,
                "On call",
                new IncidentSummary(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        monitorName,
                        "TIMEOUT",
                        null,
                        NOW.minusSeconds(60),
                        null,
                        null));
    }

    /** As spring.mail.* builds it, with the timeouts of application.yml. */
    private static JavaMailSenderImpl mailSender(int port) {
        JavaMailSenderImpl mail = new JavaMailSenderImpl();
        mail.setHost("127.0.0.1");
        mail.setPort(port);
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "10000");
        properties.setProperty("mail.smtp.writetimeout", "10000");
        mail.setJavaMailProperties(properties);
        return mail;
    }

    private static List<String> addresses(Address[] addresses) {
        return Arrays.stream(addresses)
                .map(address -> ((InternetAddress) address).getAddress())
                .toList();
    }

    /** The text and HTML parts by their type, wherever they are in the multipart tree. */
    private static Map<String, String> textParts(Part part) throws MessagingException, IOException {
        Map<String, String> found = new HashMap<>();
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                found.putAll(textParts(child));
            }
        } else if (part.isMimeType("text/plain")) {
            found.put("text/plain", (String) part.getContent());
        } else if (part.isMimeType("text/html")) {
            found.put("text/html", (String) part.getContent());
        }
        return found;
    }
}
