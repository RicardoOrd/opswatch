package io.github.ricardoord.opswatch.monitoring.engine.http;

import io.github.ricardoord.opswatch.egress.BlockedTargetException;
import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.engine.HttpObservation.Failure;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.time.Duration;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.apache.hc.client5.http.ClientProtocolException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.MessageConstraintException;
import org.apache.hc.core5.http.NoHttpResponseException;

/**
 * The table of docs/architecture/monitoring-engine.md#8-clasificación-de-fallos. Every detail is a constant of this
 * class: the message of an exception may carry an internal address or what the target sent, and the detail is stored
 * and shown to the user.
 */
final class Failures {

    static final String BLOCKED = "blocked by the SSRF protection";
    static final String HOST_NOT_FOUND = "host not found";
    static final String CONNECT_TIMED_OUT = "connection timed out";
    static final String NO_RESPONSE_IN_TIME = "no response within the timeout";
    static final String COULD_NOT_CONNECT = "could not connect";
    static final String NO_ROUTE = "no route to host";
    static final String CONNECTION_FAILED = "connection failed";
    static final String CERTIFICATE_EXPIRED = "certificate expired";
    static final String CERTIFICATE_NOT_YET_VALID = "certificate not yet valid";
    static final String CERTIFICATE_NOT_TRUSTED = "certificate not trusted";
    static final String CERTIFICATE_WRONG_HOST = "certificate not valid for the host";
    static final String TLS_FAILED = "TLS handshake failed";
    static final String HEADERS_OVER_LIMIT = "response headers over the limit";
    static final String NO_HTTP_RESPONSE = "no HTTP response";
    static final String MALFORMED_RESPONSE = "malformed HTTP response";
    static final String INVALID_LOCATION = "redirect without a valid Location";
    static final String REDIRECT_LOOP = "redirect loop";
    static final String TOO_MANY_REDIRECTS = "too many redirects";

    private Failures() {}

    static Failure of(FailureReason reason, Duration elapsed, String detail) {
        return new Failure(reason, elapsed, detail);
    }

    /**
     * What the client threw for a request that the deadline did not cut. Order matters: a blocked target is also an
     * {@link UnknownHostException}, and a refused connection also a {@code SocketException}.
     *
     * <p>{@link ClientProtocolException} wraps what the client could not parse. It also wraps the scheme and the
     * credentials in the URL that the client refuses before sending, but {@link ApacheHttpMonitorClient} never lets
     * those reach it.
     */
    static Failure classify(IOException ex, Duration elapsed) {
        return switch (ex) {
            case BlockedTargetException _ -> of(FailureReason.TARGET_BLOCKED, elapsed, BLOCKED);
            case UnknownHostException _ -> of(FailureReason.DNS_FAILURE, elapsed, HOST_NOT_FOUND);
            case ConnectTimeoutException _ -> of(FailureReason.TIMEOUT, elapsed, CONNECT_TIMED_OUT);
            case SocketTimeoutException _ -> of(FailureReason.TIMEOUT, elapsed, NO_RESPONSE_IN_TIME);
            case SSLException tls -> of(FailureReason.TLS_FAILURE, elapsed, tlsDetail(tls));
            case NoRouteToHostException _ -> of(FailureReason.CONNECTION_FAILED, elapsed, NO_ROUTE);
            case ConnectException _ -> of(FailureReason.CONNECTION_FAILED, elapsed, COULD_NOT_CONNECT);
            case MessageConstraintException _ -> of(FailureReason.PROTOCOL_ERROR, elapsed, HEADERS_OVER_LIMIT);
            case NoHttpResponseException _ -> of(FailureReason.PROTOCOL_ERROR, elapsed, NO_HTTP_RESPONSE);
            case ClientProtocolException _ -> of(FailureReason.PROTOCOL_ERROR, elapsed, MALFORMED_RESPONSE);
            default -> of(FailureReason.CONNECTION_FAILED, elapsed, CONNECTION_FAILED);
        };
    }

    /** The cause of a failed handshake is several levels down, and an expired certificate under a failed path. */
    private static String tlsDetail(SSLException ex) {
        if (causedBy(ex, CertificateExpiredException.class)) {
            return CERTIFICATE_EXPIRED;
        }
        if (causedBy(ex, CertificateNotYetValidException.class)) {
            return CERTIFICATE_NOT_YET_VALID;
        }
        if (causedBy(ex, CertPathBuilderException.class) || causedBy(ex, CertPathValidatorException.class)) {
            return CERTIFICATE_NOT_TRUSTED;
        }
        if (causedBy(ex, SSLPeerUnverifiedException.class)) {
            return CERTIFICATE_WRONG_HOST;
        }
        return TLS_FAILED;
    }

    /** The exception itself or any of its causes. */
    private static boolean causedBy(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
