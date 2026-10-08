package io.github.ricardoord.opswatch.notification.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * The URL of a webhook channel, as a request sends it: {@code https} only, checked with {@code TargetPolicy} (422 if it
 * is not allowed).
 */
public record WebhookInput(
        @NotBlank @Size(max = MAX_LENGTH) @Nullable String url) {

    public static final int MAX_LENGTH = 2048;

    @Override
    public String toString() {
        // The path of a webhook URL often carries a token
        return "WebhookInput[…]";
    }
}
