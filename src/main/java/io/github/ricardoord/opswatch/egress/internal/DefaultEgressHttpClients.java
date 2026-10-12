package io.github.ricardoord.opswatch.egress.internal;

import io.github.ricardoord.opswatch.egress.EgressClientSettings;
import io.github.ricardoord.opswatch.egress.EgressHttpClients;
import io.micrometer.core.instrument.MeterRegistry;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.ssl.TLS;
import org.apache.hc.core5.util.Timeout;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Apache HttpClient 5 with every restriction of docs/architecture/monitoring-engine.md#configuración. Nothing here is
 * read from system properties: a proxy of the environment would skip the guarded resolver.
 */
@Component
class DefaultEgressHttpClients implements EgressHttpClients {

    static final int MAX_RESPONSE_HEADER_LINE = 8 * 1024;
    static final int MAX_RESPONSE_HEADERS = 100;

    /** Waiting for a connection of the pool is an error of ours, never of the target: the caller's limit prevents it. */
    private static final Timeout POOL_WAIT = Timeout.ofSeconds(1);

    private final HostResolver resolver;
    private final IpRangeClassifier classifier;
    private final BlockedTargets blocked;

    /** Null: the trust store of the JVM, always outside the tests. */
    private final @Nullable SSLContext trust;

    @Autowired
    DefaultEgressHttpClients(HostResolver resolver, EgressProperties properties, MeterRegistry meters) {
        this(resolver, properties, meters, null);
    }

    /**
     * For the tests of this package only: a target on the loopback with a certificate of its own. Nothing outside
     * {@code egress} can reach this constructor, and the hostname is still verified.
     */
    DefaultEgressHttpClients(
            HostResolver resolver, EgressProperties properties, MeterRegistry meters, @Nullable SSLContext trust) {
        this.resolver = resolver;
        this.classifier = new IpRangeClassifier(properties.allowedPrivateBlocks());
        this.blocked = new BlockedTargets(meters);
        this.trust = trust;
    }

    @Override
    public CloseableHttpClient create(EgressClientSettings settings) {
        Timeout timeout = Timeout.of(settings.timeout());
        PoolingHttpClientConnectionManagerBuilder builder = PoolingHttpClientConnectionManagerBuilder.create();
        if (trust != null) {
            builder.setTlsSocketStrategy(
                    ClientTlsStrategyBuilder.create().setSslContext(trust).buildClassic());
        }
        PoolingHttpClientConnectionManager connections = builder.setDnsResolver(
                        new GuardedDnsResolver(resolver, classifier, blocked))
                .setMaxConnTotal(settings.maxConnections())
                .setMaxConnPerRoute(settings.maxConnections())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout)
                        .setSocketTimeout(timeout)
                        .build())
                .setDefaultTlsConfig(TlsConfig.custom()
                        .setSupportedProtocols(TLS.V_1_3, TLS.V_1_2)
                        .setHandshakeTimeout(timeout)
                        .build())
                .setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
                        .http1Config(Http1Config.custom()
                                .setMaxLineLength(MAX_RESPONSE_HEADER_LINE)
                                .setMaxHeaderCount(MAX_RESPONSE_HEADERS)
                                .build())
                        .build())
                .build();
        return HttpClients.custom()
                .setConnectionManager(connections)
                // Direct to the target, never through a proxy: neither the environment's nor a configured one
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                // First of all, before DNS and before connecting
                .addExecInterceptorFirst(EgressRequestGuard.NAME, new EgressRequestGuard(settings.kind(), blocked))
                // A new connection for every request: the latency always includes DNS, TCP and TLS, and every request
                // goes through the guarded resolver again
                .setConnectionReuseStrategy((request, response, context) -> false)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableContentCompression()
                .disableAuthCaching()
                .setUserAgent(settings.userAgent())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(timeout)
                        .setConnectionRequestTimeout(POOL_WAIT)
                        .build())
                .build();
    }
}
