package io.github.ricardoord.opswatch.shared.error;

import java.time.Duration;

/** Too many requests for one client, account or channel (429). The response carries {@code Retry-After}. */
public class RateLimitExceededException extends DomainException {

    static final String DETAIL = "Too many requests. Try again after the number of seconds in the Retry-After header.";

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super(ProblemCode.RATE_LIMITED, DETAIL);
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, rounded up and at least one: a retry any earlier would be rejected again. */
    public long retryAfterSeconds() {
        long seconds = retryAfter.toSeconds();
        return Math.max(1, retryAfter.toNanosPart() == 0 ? seconds : seconds + 1);
    }
}
