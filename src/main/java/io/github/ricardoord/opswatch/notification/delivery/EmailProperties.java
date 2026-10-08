package io.github.ricardoord.opswatch.notification.delivery;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The email notifications (docs/devops/environments.md#notificaciones). The SMTP server is {@code spring.mail.*}.
 *
 * @param from the sender of every notification, with or without a name ({@code OpsWatch <alerts@example.com>}).
 *     Required: no default address would be accepted by a real SMTP server
 */
@ConfigurationProperties("opswatch.notification.email")
public record EmailProperties(String from) {

    public EmailProperties {
        Objects.requireNonNull(from, "opswatch.notification.email.from is required");
        try {
            new InternetAddress(from, true);
        } catch (AddressException ex) {
            throw new IllegalArgumentException("opswatch.notification.email.from is not a valid address", ex);
        }
    }

    InternetAddress fromAddress() {
        try {
            return new InternetAddress(from, true);
        } catch (AddressException ex) {
            throw new IllegalStateException("Checked on construction", ex);
        }
    }
}
