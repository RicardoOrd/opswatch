package io.github.ricardoord.opswatch.egress.internal;

import io.github.ricardoord.opswatch.egress.BlockedTargetException;
import io.github.ricardoord.opswatch.egress.HeaderPolicy;
import io.github.ricardoord.opswatch.egress.HeaderViolation;
import io.github.ricardoord.opswatch.egress.RequestHeader;
import io.github.ricardoord.opswatch.egress.TargetKind;
import io.github.ricardoord.opswatch.shared.error.TargetNotAllowedException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks every request before it leaves, again, what was checked when it was saved: the URL against the rules of layer
 * 1 that need no DNS, and the headers against {@link HeaderPolicy} (layer 4). The rules change between versions and the
 * database can be written outside the API: what was valid once must not leave because of that.
 *
 * <p>It is the first link of the execution chain of the client, before any DNS lookup or connection, and before the
 * client adds headers of its own ({@code Host}, {@code User-Agent}). A plain request interceptor would not do: the
 * client runs those after connecting. Each redirect hop is a new request, so it goes through here as well.
 */
final class EgressRequestGuard implements ExecChainHandler {

    static final String NAME = "egress-request-guard";

    private static final Logger log = LoggerFactory.getLogger(EgressRequestGuard.class);

    private final TargetKind kind;
    private final BlockedTargets blockedTargets;

    EgressRequestGuard(TargetKind kind, BlockedTargets blocked) {
        this.kind = kind;
        this.blockedTargets = blocked;
    }

    @Override
    public ClassicHttpResponse execute(ClassicHttpRequest request, ExecChain.Scope scope, ExecChain chain)
            throws IOException, HttpException {
        check(request);
        return chain.proceed(request, scope);
    }

    /** @throws BlockedTargetException if the URL or a header breaks a rule */
    void check(ClassicHttpRequest request) throws BlockedTargetException {
        URI uri = uriOf(request);
        String host = uri.getHost() == null ? "?" : uri.getHost();
        try {
            TargetUrlParser.parse(uri.toString(), kind);
        } catch (TargetNotAllowedException ex) {
            blockedTargets.count(BlockedTargets.Reason.URL);
            throw blocked(host, ex.getMessage());
        }
        List<RequestHeader> headers = Arrays.stream(request.getHeaders())
                .map(header -> new RequestHeader(header.getName(), header.getValue() == null ? "" : header.getValue()))
                .toList();
        Optional<HeaderViolation> violation = HeaderPolicy.check(headers);
        if (violation.isPresent()) {
            blockedTargets.count(BlockedTargets.Reason.HEADER);
            throw blocked(
                    host,
                    "The header " + violation.get().field("headers") + " "
                            + violation.get().message() + ".");
        }
    }

    private URI uriOf(ClassicHttpRequest request) throws BlockedTargetException {
        try {
            return request.getUri();
        } catch (URISyntaxException ex) {
            blockedTargets.count(BlockedTargets.Reason.URL);
            throw blocked("?", "The URL is not valid.");
        }
    }

    /** The host is the one in the request, never an address; the rule says why. */
    private static BlockedTargetException blocked(String host, String rule) {
        log.atInfo()
                .addKeyValue("event.category", "security")
                .addKeyValue("event.action", "egress.target_blocked")
                .addKeyValue("url.domain", host)
                .log("Request to {} blocked before leaving: {}", host, rule);
        return new BlockedTargetException(host, rule);
    }
}
