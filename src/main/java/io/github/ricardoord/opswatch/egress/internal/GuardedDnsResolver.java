package io.github.ricardoord.opswatch.egress.internal;

import io.github.ricardoord.opswatch.egress.BlockedTargetException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import org.apache.hc.client5.http.DnsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 2 (docs/security/ssrf-protection.md#capa-2-resolución-dns-con-fijación-de-ip): the client connects exactly to
 * the addresses this returns, so there is no second resolution an attacker could change between the check and the
 * connection.
 *
 * <p>Every address is classified: if any one is blocked, the whole host is, instead of picking the good one out of a
 * mixed set. An address literal is classified as it is, without asking any resolver. Names go through the same
 * {@link HostResolver} as layer 1, so the tests decide what each name resolves to. There is no deadline here: the
 * resolution of the system cannot be interrupted (docs/architecture/monitoring-engine.md#dns).
 */
final class GuardedDnsResolver implements DnsResolver {

    private static final Logger log = LoggerFactory.getLogger(GuardedDnsResolver.class);

    static final String BLOCKED_ADDRESS = "It resolves to an address that is not public.";

    private final HostResolver resolver;
    private final IpRangeClassifier classifier;
    private final BlockedTargets blocked;

    GuardedDnsResolver(HostResolver resolver, IpRangeClassifier classifier, BlockedTargets blocked) {
        this.resolver = resolver;
        this.classifier = classifier;
        this.blocked = blocked;
    }

    /**
     * @throws BlockedTargetException if any address of the host is blocked
     * @throws UnknownHostException if the name does not resolve
     */
    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        Optional<InetAddress> literal = IpLiterals.parse(withoutBrackets(host));
        List<InetAddress> addresses = literal.isPresent() ? List.of(literal.get()) : resolver.resolve(host);
        if (addresses.isEmpty()) {
            throw new UnknownHostException(host);
        }
        if (!addresses.stream().allMatch(classifier::isAllowed)) {
            log.atInfo()
                    .addKeyValue("event.category", "security")
                    .addKeyValue("event.action", "egress.target_blocked")
                    .addKeyValue("url.domain", host)
                    .log("Connection to {} blocked: not a public address", host);
            blocked.count(BlockedTargets.Reason.ADDRESS);
            throw new BlockedTargetException(host, BLOCKED_ADDRESS);
        }
        return addresses.toArray(InetAddress[]::new);
    }

    /** No reverse lookup: the name stays the one the request asked for. */
    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }

    /** An IPv6 literal may come as it is written in a URL. */
    private static String withoutBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }
}
