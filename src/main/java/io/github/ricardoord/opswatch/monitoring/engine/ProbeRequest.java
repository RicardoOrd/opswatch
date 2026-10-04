package io.github.ricardoord.opswatch.monitoring.engine;

import io.github.ricardoord.opswatch.egress.RequestHeader;
import io.github.ricardoord.opswatch.monitoring.domain.ProbeMethod;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The request of one check.
 *
 * @param headers decrypted, so their values never appear in {@link #toString()}; the client checks them again with
 *     {@code HeaderPolicy} before every request, and sends them only to the origin of {@code url}
 * @param timeout for the whole check, redirects included
 * @param followRedirects if false, a {@code 3xx} is the final response
 */
public record ProbeRequest(
        URI url, ProbeMethod method, List<RequestHeader> headers, Duration timeout, boolean followRedirects) {

    public ProbeRequest {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(timeout, "timeout");
        headers = List.copyOf(headers);
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    /** Header names only, and the URL without credentials or query, which may carry a token. */
    @Override
    public String toString() {
        return "ProbeRequest[method=" + method
                + ", url=" + url.getScheme() + "://" + url.getHost() + (url.getPort() == -1 ? "" : ":" + url.getPort())
                + url.getRawPath()
                + ", headers=" + headers.stream().map(RequestHeader::name).toList()
                + ", timeout=" + timeout
                + ", followRedirects=" + followRedirects + "]";
    }
}
