package io.github.ricardoord.opswatch.monitoring.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.egress.BlockedTargetException;
import io.github.ricardoord.opswatch.monitoring.FailureReason;
import io.github.ricardoord.opswatch.monitoring.engine.HttpObservation.Failure;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.time.Duration;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.apache.hc.client5.http.ClientProtocolException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.apache.hc.core5.http.MessageConstraintException;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.apache.hc.core5.http.ParseException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every row of docs/architecture/monitoring-engine.md#8-clasificación-de-fallos, including those a test against a
 * simulated target cannot produce reliably (no route, an expired certificate). The messages carry what a detail must
 * never repeat: an internal address.
 */
class FailuresTest {

    private static final String SECRET = "10.0.0.5";

    static Stream<Arguments> table() {
        return Stream.of(
                row(
                        new BlockedTargetException("internal." + SECRET, "rule"),
                        FailureReason.TARGET_BLOCKED,
                        Failures.BLOCKED),
                row(new UnknownHostException(SECRET), FailureReason.DNS_FAILURE, Failures.HOST_NOT_FOUND),
                row(new ConnectTimeoutException(SECRET), FailureReason.TIMEOUT, Failures.CONNECT_TIMED_OUT),
                row(new SocketTimeoutException(SECRET), FailureReason.TIMEOUT, Failures.NO_RESPONSE_IN_TIME),
                row(
                        new HttpHostConnectException("Connect to " + SECRET + " failed"),
                        FailureReason.CONNECTION_FAILED,
                        Failures.COULD_NOT_CONNECT),
                row(new ConnectException(SECRET), FailureReason.CONNECTION_FAILED, Failures.COULD_NOT_CONNECT),
                row(new NoRouteToHostException(SECRET), FailureReason.CONNECTION_FAILED, Failures.NO_ROUTE),
                row(
                        new SocketException("Connection reset " + SECRET),
                        FailureReason.CONNECTION_FAILED,
                        Failures.CONNECTION_FAILED),
                row(
                        tls(new CertPathValidatorException(SECRET, new CertificateExpiredException(SECRET))),
                        FailureReason.TLS_FAILURE,
                        Failures.CERTIFICATE_EXPIRED),
                row(
                        tls(new CertificateNotYetValidException(SECRET)),
                        FailureReason.TLS_FAILURE,
                        Failures.CERTIFICATE_NOT_YET_VALID),
                row(
                        tls(new CertPathBuilderException(SECRET)),
                        FailureReason.TLS_FAILURE,
                        Failures.CERTIFICATE_NOT_TRUSTED),
                row(
                        new SSLPeerUnverifiedException("Certificate for <" + SECRET + "> doesn't match"),
                        FailureReason.TLS_FAILURE,
                        Failures.CERTIFICATE_WRONG_HOST),
                row(new SSLException(SECRET), FailureReason.TLS_FAILURE, Failures.TLS_FAILED),
                row(new MessageConstraintException(SECRET), FailureReason.PROTOCOL_ERROR, Failures.HEADERS_OVER_LIMIT),
                row(new NoHttpResponseException(SECRET), FailureReason.PROTOCOL_ERROR, Failures.NO_HTTP_RESPONSE),
                row(
                        new ClientProtocolException(new ParseException("Invalid header: " + SECRET)),
                        FailureReason.PROTOCOL_ERROR,
                        Failures.MALFORMED_RESPONSE),
                row(new IOException(SECRET), FailureReason.CONNECTION_FAILED, Failures.CONNECTION_FAILED));
    }

    @ParameterizedTest
    @MethodSource("table")
    void classifiesWithAGenericDetailOfItsOwn(IOException ex, FailureReason reason, String detail) {
        Failure failure = Failures.classify(ex, Duration.ofMillis(42));

        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.detail()).isEqualTo(detail).doesNotContain(SECRET);
        assertThat(failure.elapsed()).isEqualTo(Duration.ofMillis(42));
    }

    private static Arguments row(IOException ex, FailureReason reason, String detail) {
        return Arguments.of(ex, reason, detail);
    }

    /** As the JDK reports a failed handshake: the cause a few levels down. */
    private static SSLHandshakeException tls(Exception cause) {
        SSLHandshakeException ex = new SSLHandshakeException("PKIX path validation failed: " + SECRET);
        ex.initCause(new SSLException("wrapped", cause));
        return ex;
    }
}
