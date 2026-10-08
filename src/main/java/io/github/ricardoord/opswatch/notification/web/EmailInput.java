package io.github.ricardoord.opswatch.notification.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The recipients of an email channel, as a request sends them. How many there may be, and that none repeats, the service
 * checks: the limit is configuration ({@code opswatch.limits.recipients-per-channel}).
 *
 * @param recipients printable ASCII only, which also keeps line breaks out of the headers of an email
 */
public record EmailInput(
        @NotEmpty @Nullable
        List<
                        @NotBlank @Size(max = MAX_LENGTH)
                        @Email(regexp = ASCII, message = "must be a valid email address in ASCII") String>
                recipients) {

    /** The longest address SMTP allows. */
    public static final int MAX_LENGTH = 254;

    static final String ASCII = "[\\x21-\\x7E]+";

    public EmailInput {
        recipients = recipients == null ? null : List.copyOf(recipients);
    }
}
