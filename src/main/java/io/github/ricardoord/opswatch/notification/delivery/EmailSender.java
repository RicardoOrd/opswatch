package io.github.ricardoord.opswatch.notification.delivery;

import io.github.ricardoord.opswatch.notification.application.ChannelConfig;
import io.github.ricardoord.opswatch.notification.domain.ChannelType;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends a notice to the recipients of an email channel with Spring Mail: one message, multipart in text and in HTML,
 * from {@code opswatch.notification.email.from}. The SMTP server and its timeouts are {@code spring.mail.*}: without
 * the timeouts, Jakarta Mail would wait for a slow server forever (T-33).
 *
 * <p>The reason of a failure says what went wrong with the server, never which recipient: the API shows it, and the API
 * only shows the recipients masked.
 */
@Component
@ConditionalOnBooleanProperty(name = "opswatch.notification.delivery.enabled", matchIfMissing = true)
@EnableConfigurationProperties(EmailProperties.class)
class EmailSender implements ChannelSender {

    /** The id of the delivery, the same on every attempt: a reader can tell a repeated message by it. */
    static final String DELIVERY_HEADER = "X-OpsWatch-Delivery-Id";

    private final JavaMailSender mail;
    private final EmailTemplates templates;
    private final EmailProperties properties;
    private final Clock clock;

    EmailSender(JavaMailSender mail, EmailTemplates templates, EmailProperties properties, Clock clock) {
        this.mail = mail;
        this.templates = templates;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public ChannelType type() {
        return ChannelType.EMAIL;
    }

    @Override
    public void send(Notice notice, ChannelConfig config) {
        if (!(config instanceof ChannelConfig.Email email)) {
            throw new IllegalArgumentException("Not the configuration of an email channel: " + config);
        }
        EmailTemplates.EmailContent content = templates.render(notice);
        MimeMessage message = mail.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.fromAddress());
            helper.setTo(email.recipients().toArray(String[]::new));
            helper.setSubject(content.subject());
            helper.setText(content.text(), content.html());
            helper.setSentDate(Date.from(clock.instant()));
            message.setHeader(DELIVERY_HEADER, notice.deliveryId().toString());
            // RFC 3834: an automatic message, so that auto-replies and out-of-office answers are not sent back
            message.setHeader("Auto-Submitted", "auto-generated");
        } catch (MessagingException ex) {
            throw new IllegalStateException("The email of delivery " + notice.deliveryId() + " could not be built", ex);
        }
        try {
            mail.send(message);
        } catch (MailException ex) {
            throw new DeliveryFailedException(reasonOf(ex), ex);
        }
    }

    /** What went wrong, from the exceptions under the one of Spring Mail. */
    static String reasonOf(MailException failure) {
        if (failure instanceof MailAuthenticationException) {
            return "SMTP authentication failed";
        }
        Set<Throwable> seen = new HashSet<>();
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        @Nullable String reason = null;
        while (!pending.isEmpty() && reason == null) {
            Throwable current = pending.poll();
            if (!seen.add(current)) {
                continue;
            }
            reason = reasonOf(current);
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            if (current instanceof MessagingException messaging && messaging.getNextException() != null) {
                pending.add(messaging.getNextException());
            }
            if (current instanceof MailSendException send) {
                pending.addAll(send.getFailedMessages().values());
            }
        }
        return reason != null ? reason : "SMTP send failed";
    }

    private static @Nullable String reasonOf(Throwable failure) {
        return switch (failure) {
            case AuthenticationFailedException _ -> "SMTP authentication failed";
            case SocketTimeoutException _ -> "SMTP server timed out";
            case ConnectException _ -> "SMTP server unreachable";
            case NoRouteToHostException _ -> "SMTP server unreachable";
            case UnknownHostException _ -> "SMTP server not found";
            case SendFailedException _ -> "SMTP server rejected the message or a recipient";
            default -> null;
        };
    }
}
