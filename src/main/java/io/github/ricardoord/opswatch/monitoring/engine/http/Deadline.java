package io.github.ricardoord.opswatch.monitoring.engine.http;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.hc.core5.concurrent.Cancellable;
import org.jspecify.annotations.Nullable;

/**
 * The limit over a whole check, redirects included: when it passes, it cancels the request in flight, which closes its
 * connection at once. Neither the timeout of a read nor that of the response can stop a target that sends a byte now
 * and then (slowloris), and the client cannot lower its connection timeout per request.
 */
final class Deadline implements AutoCloseable {

    private @Nullable Cancellable current;
    private boolean expired;
    private @Nullable ScheduledFuture<?> timer;

    private Deadline() {}

    static Deadline start(ScheduledExecutorService timers, Duration after) {
        Deadline deadline = new Deadline();
        deadline.timer = timers.schedule(deadline::expire, after.toNanos(), TimeUnit.NANOSECONDS);
        return deadline;
    }

    /** The request about to be sent; if the deadline already passed, it is cancelled before it leaves. */
    synchronized void track(Cancellable request) {
        current = request;
        if (expired) {
            request.cancel();
        }
    }

    synchronized boolean expired() {
        return expired;
    }

    private synchronized void expire() {
        expired = true;
        if (current != null) {
            current.cancel();
        }
    }

    @Override
    public void close() {
        if (timer != null) {
            timer.cancel(false);
        }
    }
}
