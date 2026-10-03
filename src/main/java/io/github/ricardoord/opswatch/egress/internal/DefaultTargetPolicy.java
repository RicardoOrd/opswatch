package io.github.ricardoord.opswatch.egress.internal;

import io.github.ricardoord.opswatch.egress.TargetKind;
import io.github.ricardoord.opswatch.egress.TargetPolicy;
import io.github.ricardoord.opswatch.egress.internal.TargetUrlParser.ParsedTarget;
import io.github.ricardoord.opswatch.egress.internal.TargetUrlParser.Rejection;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Layer 1: the syntax of {@link TargetUrlParser}, then every address the host resolves to now through
 * {@link IpRangeClassifier}.
 *
 * <p>A name that does not resolve, or not within {@code opswatch.egress.save-resolution-timeout}, is let through: its
 * DNS may not exist yet, and layer 2 decides on every connection anyway. The deadline matters because the host is the
 * user's to choose, and so is the speed of its DNS. Lookups run on virtual threads, at most
 * {@value #MAX_CONCURRENT_RESOLUTIONS} at a time: one that never answers keeps its permit until the system resolver
 * gives up, so slow names cannot pile up without bound. Without a free permit, the URL goes through unresolved.
 */
@Component
@EnableConfigurationProperties(EgressProperties.class)
class DefaultTargetPolicy implements TargetPolicy {

    static final int MAX_CONCURRENT_RESOLUTIONS = 32;

    private static final Logger log = LoggerFactory.getLogger(DefaultTargetPolicy.class);

    private final HostResolver resolver;
    private final IpRangeClassifier classifier;
    private final Duration resolutionTimeout;
    private final Semaphore resolutions = new Semaphore(MAX_CONCURRENT_RESOLUTIONS);
    private final ThreadFactory lookups =
            Thread.ofVirtual().name("egress-save-lookup-", 0).factory();

    DefaultTargetPolicy(HostResolver resolver, EgressProperties properties) {
        this.resolver = resolver;
        this.classifier = new IpRangeClassifier(properties.allowedPrivateBlocks());
        this.resolutionTimeout = properties.saveResolutionTimeout();
    }

    @Override
    public URI validate(String url, TargetKind kind) {
        ParsedTarget target = TargetUrlParser.parse(url, kind);
        InetAddress literal = target.literal();
        List<InetAddress> addresses = literal != null ? List.of(literal) : resolve(target.host());
        // Every address: a name that resolves to a public address and a private one is rejected
        if (!addresses.stream().allMatch(classifier::isAllowed)) {
            log.atInfo()
                    .addKeyValue("event.category", "security")
                    .addKeyValue("event.action", "egress.target_rejected")
                    .addKeyValue("url.domain", target.host())
                    .log("Target {} rejected on saving: not a public address", target.host());
            throw Rejection.HOST.exception();
        }
        return target.uri();
    }

    /** Empty when it cannot tell in time: the URL then goes through, and layer 2 decides. */
    private List<InetAddress> resolve(String host) {
        if (!resolutions.tryAcquire()) {
            log.warn("No permit left to resolve {} on saving: accepted unresolved", host);
            return List.of();
        }
        CompletableFuture<List<InetAddress>> lookup = new CompletableFuture<>();
        lookups.newThread(() -> {
                    try {
                        lookup.complete(resolver.resolve(host));
                    } catch (UnknownHostException | RuntimeException ex) {
                        lookup.completeExceptionally(ex);
                    } finally {
                        resolutions.release();
                    }
                })
                .start();
        try {
            return lookup.get(resolutionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            log.info("{} did not resolve within {} on saving: accepted unresolved", host, resolutionTimeout);
            return List.of();
        } catch (ExecutionException ex) {
            if (!(ex.getCause() instanceof UnknownHostException)) {
                log.warn("Resolving {} on saving failed: accepted unresolved", host, ex.getCause());
            }
            return List.of();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}
