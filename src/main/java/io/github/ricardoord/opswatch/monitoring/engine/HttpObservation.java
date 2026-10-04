package io.github.ricardoord.opswatch.monitoring.engine;

import io.github.ricardoord.opswatch.monitoring.FailureReason;
import java.time.Duration;
import java.util.Objects;

/** What a check saw on the network, before anyone judges it. */
public sealed interface HttpObservation {

    /**
     * There was an HTTP response.
     *
     * @param responseTime from the start of the check to the headers of the final response, redirects included
     */
    record Response(int statusCode, Duration responseTime, int redirectsFollowed) implements HttpObservation {

        public Response {
            Objects.requireNonNull(responseTime, "responseTime");
        }
    }

    /**
     * There was no usable response.
     *
     * @param detail a short generic text of our own, never the message of an exception nor anything the target sent:
     *     it is stored and shown to the user (docs/security/ssrf-protection.md#capa-5-restricciones-de-la-respuesta)
     */
    record Failure(FailureReason reason, Duration elapsed, String detail) implements HttpObservation {

        public Failure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(elapsed, "elapsed");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
