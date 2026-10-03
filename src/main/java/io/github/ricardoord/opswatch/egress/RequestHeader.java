package io.github.ricardoord.opswatch.egress;

import java.util.Objects;

/**
 * A header that a user adds to the requests to a target. Its value may be a credential of a third party: it is never
 * printed, and only {@link HeaderPolicy} decides whether it may leave.
 */
public record RequestHeader(String name, String value) {

    public RequestHeader {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return "RequestHeader[name=" + name + ", value=<redacted>]";
    }
}
