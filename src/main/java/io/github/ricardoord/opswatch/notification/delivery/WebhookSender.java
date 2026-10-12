package io.github.ricardoord.opswatch.notification.delivery;

import io.github.ricardoord.opswatch.egress.BlockedTargetException;
import io.github.ricardoord.opswatch.egress.EgressClientSettings;
import io.github.ricardoord.opswatch.egress.EgressHttpClients;
import io.github.ricardoord.opswatch.egress.TargetKind;
import io.github.ricardoord.opswatch.notification.application.ChannelConfig;
import io.github.ricardoord.opswatch.notification.application.DeliveryProperties;
import io.github.ricardoord.opswatch.notification.domain.ChannelType;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.ClientProtocolException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Sends a notice to the URL of a webhook channel (docs/api/webhooks.md): a {@code POST} of a JSON body that OpsWatch
 * generates, signed with the secret of the channel, through a client of {@code egress} for {@link TargetKind#WEBHOOK}.
 * So the URL is {@code https} and resolves to a public address on every send, not only when it was saved (T-30).
 *
 * <p>Any answer outside {@code 2xx} is a failed attempt, a {@code 3xx} too: redirects are never followed, so the
 * signed body never leaves for another URL. A receiver that takes longer than {@code timeout} to answer is a failed
 * attempt as well. The body of the answer is never read.
 *
 * <p>The reason of a failure never carries the URL, which can hold a token: the API shows it to readers who only see
 * the URL masked.
 */
@Component
@ConditionalOnBooleanProperty(name = "opswatch.notification.delivery.enabled", matchIfMissing = true)
@EnableConfigurationProperties(WebhookProperties.class)
class WebhookSender implements ChannelSender, AutoCloseable {

    static final String TIMED_OUT = "receiver timed out";

    private final CloseableHttpClient client;
    private final ScheduledThreadPoolExecutor timers;
    private final Duration timeout;
    private final Clock clock;

    WebhookSender(EgressHttpClients clients, WebhookProperties properties, DeliveryProperties delivery, Clock clock) {
        // The worker sends at most a batch at once, so no send waits for a connection of the pool
        this.client = clients.create(new EgressClientSettings(
                TargetKind.WEBHOOK, delivery.batchSize(), properties.timeout(), properties.userAgent()));
        this.timers = new ScheduledThreadPoolExecutor(
                1, Thread.ofPlatform().name("webhook-deadline").daemon().factory());
        // A send that ends in time cancels its timer: it must not stay in the queue until it would have fired
        this.timers.setRemoveOnCancelPolicy(true);
        this.timeout = properties.timeout();
        this.clock = clock;
    }

    @Override
    public ChannelType type() {
        return ChannelType.WEBHOOK;
    }

    @Override
    public void send(Notice notice, ChannelConfig config) {
        if (!(config instanceof ChannelConfig.Webhook webhook)) {
            throw new IllegalArgumentException("Not the configuration of a webhook channel: " + config);
        }
        byte[] body = WebhookPayloads.of(notice);
        HttpPost request = new HttpPost(URI.create(webhook.url()));
        request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        request.setHeader(
                WebhookSigner.SIGNATURE_HEADER,
                WebhookSigner.sign(webhook.signingSecret(), clock.instant().getEpochSecond(), body));
        request.setHeader(WebhookSigner.VERSION_HEADER, WebhookSigner.VERSION);
        int status;
        try (SendDeadline deadline = SendDeadline.start(timers, timeout)) {
            try {
                status = statusOf(request, deadline);
            } catch (IOException ex) {
                throw new DeliveryFailedException(deadline.expired() ? TIMED_OUT : reasonOf(ex), ex);
            } catch (RuntimeException ex) {
                // Once the deadline cuts the request, whatever the client throws is the cut
                if (deadline.expired()) {
                    throw new DeliveryFailedException(TIMED_OUT, ex);
                }
                throw ex;
            }
        }
        if (status / 100 == 3) {
            throw new DeliveryFailedException("receiver answered " + status + "; redirects are not followed", null);
        }
        if (status / 100 != 2) {
            throw new DeliveryFailedException("receiver answered " + status, null);
        }
    }

    /** Up to the status of the answer: its body is never read. */
    private int statusOf(HttpPost request, SendDeadline deadline) throws IOException {
        deadline.track(request);
        ClassicHttpResponse response = client.executeOpen(null, request, null);
        try {
            return response.getCode();
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

    /**
     * What the client threw for a send that the deadline did not cut. Order matters: a blocked target is also an
     * {@link UnknownHostException}, and a refused connection also a {@code SocketException}.
     */
    static String reasonOf(IOException ex) {
        return switch (ex) {
            case BlockedTargetException _ -> "target not allowed";
            case UnknownHostException _ -> "receiver host not found";
            case ConnectTimeoutException _ -> TIMED_OUT;
            case SocketTimeoutException _ -> TIMED_OUT;
            case SSLException _ -> "TLS handshake failed";
            case NoRouteToHostException _ -> "receiver unreachable";
            case ConnectException _ -> "receiver unreachable";
            // Also a scheme other than https or credentials in the URL, which the client refuses before sending
            case ClientProtocolException _ -> "request refused or answer not understood";
            default -> "connection failed";
        };
    }

    @Override
    public void close() throws IOException {
        timers.shutdownNow();
        client.close();
    }
}
