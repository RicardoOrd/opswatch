package io.github.ricardoord.opswatch.monitoring.engine.http;

import io.github.ricardoord.opswatch.egress.EgressClientSettings;
import io.github.ricardoord.opswatch.egress.EgressHttpClients;
import io.github.ricardoord.opswatch.egress.RequestHeader;
import io.github.ricardoord.opswatch.egress.TargetKind;
import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSettings;
import io.github.ricardoord.opswatch.monitoring.engine.HttpMonitorClient;
import io.github.ricardoord.opswatch.monitoring.engine.HttpObservation;
import io.github.ricardoord.opswatch.monitoring.engine.MonitoringEngineProperties;
import io.github.ricardoord.opswatch.monitoring.engine.ProbeRequest;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@link HttpMonitorClient} over a client of {@code egress}, which already resolves through the guarded resolver and
 * checks the URL and the headers of every request before it leaves (docs/architecture/monitoring-engine.md#6-cliente-http).
 * Here: the deadline, the redirects followed by hand, and turning what happened into an observation.
 */
@Component
@EnableConfigurationProperties(MonitoringEngineProperties.class)
class ApacheHttpMonitorClient implements HttpMonitorClient, AutoCloseable {

    private final CloseableHttpClient client;
    private final ScheduledThreadPoolExecutor timers;
    private final int maxRedirects;
    private final Duration deadlineGrace;

    ApacheHttpMonitorClient(EgressHttpClients clients, MonitoringEngineProperties properties) {
        // The semaphore of the engine keeps the checks in flight within the pool, so none waits for a connection. The
        // timeouts are the longest a monitor may have: the deadline cuts each check to its own
        this.client = clients.create(new EgressClientSettings(
                TargetKind.MONITOR,
                properties.maxConcurrentChecks(),
                Duration.ofMillis(MonitorSettings.MAX_TIMEOUT_MS),
                properties.userAgent()));
        this.timers = new ScheduledThreadPoolExecutor(
                1, Thread.ofPlatform().name("check-deadline").daemon().factory());
        // A check that ends in time cancels its timer: it must not stay in the queue until it would have fired
        this.timers.setRemoveOnCancelPolicy(true);
        this.maxRedirects = properties.maxRedirects();
        this.deadlineGrace = properties.deadlineGrace();
    }

    @Override
    public HttpObservation probe(ProbeRequest probe) {
        long start = System.nanoTime();
        try (Deadline deadline = Deadline.start(timers, probe.timeout().plus(deadlineGrace))) {
            return follow(probe, start, deadline);
        }
    }

    private HttpObservation follow(ProbeRequest probe, long start, Deadline deadline) {
        URI url = probe.url();
        Set<URI> visited = new HashSet<>(Set.of(url));
        for (int redirects = 0; ; redirects++) {
            if (Redirects.refusedByTheClient(url)) {
                return Failures.of(FailureReason.TARGET_BLOCKED, elapsed(start), Failures.BLOCKED);
            }
            if (deadline.expired()) {
                return timedOut(start);
            }
            // Once the deadline cuts the request, whatever the client throws is the cut: a closed socket, an aborted
            // request, or an endpoint released under it
            Hop hop;
            try {
                hop = send(request(probe, url), url, deadline);
            } catch (IOException ex) {
                return deadline.expired() ? timedOut(start) : Failures.classify(ex, elapsed(start));
            } catch (RuntimeException ex) {
                if (deadline.expired()) {
                    return timedOut(start);
                }
                throw ex;
            }
            if (!probe.followRedirects() || !Redirects.isRedirect(hop.statusCode())) {
                return new HttpObservation.Response(
                        hop.statusCode(), Duration.ofNanos(hop.receivedAt() - start), redirects);
            }
            if (redirects == maxRedirects) {
                return Failures.of(FailureReason.TOO_MANY_REDIRECTS, elapsed(start), Failures.TOO_MANY_REDIRECTS);
            }
            if (hop.location().isEmpty()) {
                return Failures.of(FailureReason.PROTOCOL_ERROR, elapsed(start), Failures.INVALID_LOCATION);
            }
            url = hop.location().get();
            if (!visited.add(url)) {
                return Failures.of(FailureReason.TOO_MANY_REDIRECTS, elapsed(start), Failures.REDIRECT_LOOP);
            }
        }
    }

    /**
     * A new request for every hop, so that each one goes through every check of {@code egress} again. The method never
     * changes: {@code 301}, {@code 302} and {@code 303} turn other methods into {@code GET} except {@code HEAD}, and
     * {@code 307} and {@code 308} keep it, so neither {@code GET} nor {@code HEAD} ever changes.
     */
    private static HttpUriRequestBase request(ProbeRequest probe, URI url) {
        HttpUriRequestBase request = new HttpUriRequestBase(probe.method().name(), url);
        if (Redirects.sameOrigin(probe.url(), url)) {
            for (RequestHeader header : probe.headers()) {
                request.addHeader(header.name(), header.value());
            }
        }
        return request;
    }

    /** Up to the headers of the response: the body is never read. */
    private Hop send(HttpUriRequestBase request, URI url, Deadline deadline) throws IOException {
        deadline.track(request);
        ClassicHttpResponse response = client.executeOpen(null, request, null);
        long receivedAt = System.nanoTime();
        try {
            int statusCode = response.getCode();
            Optional<URI> location =
                    Redirects.isRedirect(statusCode) ? Redirects.next(url, response) : Optional.empty();
            return new Hop(statusCode, location, receivedAt);
        } finally {
            // Dropping the connection instead of draining the body: closing the response would read it to the end
            request.cancel();
            try {
                response.close();
            } catch (IOException ignored) {
                // The socket the cancel just closed
            }
        }
    }

    private static HttpObservation timedOut(long start) {
        return Failures.of(FailureReason.TIMEOUT, elapsed(start), Failures.NO_RESPONSE_IN_TIME);
    }

    private static Duration elapsed(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
    }

    @Override
    public void close() throws IOException {
        timers.shutdownNow();
        client.close();
    }

    /** @param receivedAt {@link System#nanoTime()} when its headers arrived */
    private record Hop(int statusCode, Optional<URI> location, long receivedAt) {}
}
