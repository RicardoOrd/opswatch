package io.github.ricardoord.opswatch.monitoring.web;

import io.github.ricardoord.opswatch.egress.HeaderPolicy;
import io.github.ricardoord.opswatch.egress.RequestHeader;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A header in a request, write-only: no response ever carries its value back. {@link HeaderPolicy} decides which are
 * allowed; here only that both parts are present.
 */
public record HeaderInput(
        @Schema(example = "Authorization") @NotNull @Nullable
        String name,

        @Schema(
                example = "Bearer s3cr3t",
                accessMode = Schema.AccessMode.WRITE_ONLY,
                maxLength = HeaderPolicy.MAX_VALUE_BYTES,
                description = "Printable ASCII. Never returned")
        @NotNull
        @Nullable
        String value) {

    /** Spaces around a value are not part of it (RFC 9110). A name is not touched: a space in it is an error. */
    public HeaderInput {
        value = value == null ? null : value.strip();
    }

    /** Bean Validation has already rejected a missing part. */
    RequestHeader toHeader() {
        return new RequestHeader(Objects.requireNonNull(name), Objects.requireNonNull(value));
    }

    /** Never the value: a log line or a validation report can print the whole request. */
    @Override
    public String toString() {
        return "HeaderInput[name=" + name + ", value=<redacted>]";
    }
}
