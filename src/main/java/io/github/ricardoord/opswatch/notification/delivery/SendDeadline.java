package io.github.ricardoord.opswatch.notification.delivery;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.hc.core5.concurrent.Cancellable;
import org.jspecify.annotations.Nullable;

/**
 * The limit over a whole webhook, as the deadline of a check in {@code monitoring}: when it passes, it cancels the
 * request in flight, which closes its connection at once. The timeouts of the client bound each read, not a receiver
 * that sends a byte now and then (T-33).
 */
final class SendDeadline implements AutoCloseable {

    private @Nullable Cancellable current;
    private boolean expired;
    private @Nullable ScheduledFuture<?> timer;

    private SendDeadline() {}

    static SendDeadline start(ScheduledExecutorService timers, Duration after) {
        SendDeadline deadline = new SendDeadline();
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
