package io.github.ricardoord.opswatch.monitoring.engine.http;

import io.github.ricardoord.opswatch.egress.TargetKind;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.apache.hc.client5.http.utils.URIUtils;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpResponse;

/** The rules of docs/architecture/monitoring-engine.md#7-redirects that do not need the network. */
final class Redirects {

    private static final Set<Integer> CODES = Set.of(301, 302, 303, 307, 308);

    private Redirects() {}

    static boolean isRedirect(int statusCode) {
        return CODES.contains(statusCode);
    }

    /**
     * The URL a redirect points to, resolved against the current one and without its fragment, which is never sent.
     *
     * @return empty if there is no {@code Location}, more than one, or one that is not a URI reference
     */
    static Optional<URI> next(URI current, HttpResponse response) {
        Header[] locations = response.getHeaders(HttpHeaders.LOCATION);
        if (locations.length != 1
                || locations[0].getValue() == null
                || locations[0].getValue().isBlank()) {
            return Optional.empty();
        }
        try {
            URI location = new URI(locations[0].getValue());
            // Against an empty path, a relative reference would be glued to the host
            URI base = current.getRawPath() == null || current.getRawPath().isEmpty() ? current.resolve("/") : current;
            // RFC 3986, which URI.resolve does not follow for a reference with only a query
            String next = URIUtils.resolve(base, location).toString();
            int fragment = next.indexOf('#');
            return Optional.of(new URI(fragment == -1 ? next : next.substring(0, fragment)));
        } catch (URISyntaxException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * What the egress client refuses itself, before its execution chain: a scheme other than http and https, no host,
     * or credentials in the URL. It does so with a {@code ClientProtocolException}, the same exception it uses for a
     * response it cannot parse; recognising these here keeps them {@code TARGET_BLOCKED} and not a protocol error of the
     * target. They never leave either way.
     */
    static boolean refusedByTheClient(URI url) {
        String scheme = url.getScheme();
        return scheme == null
                || !TargetKind.MONITOR.allows(scheme.toLowerCase(Locale.ROOT))
                || url.getHost() == null
                || url.getRawUserInfo() != null;
    }

    /**
     * Same scheme, host and port, the port of the scheme when there is none: the only hops that get the headers of the
     * monitor, as browsers and curl do, so that a redirect does not hand an {@code Authorization} to another host.
     */
    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    private static int port(URI url) {
        if (url.getPort() != -1) {
            return url.getPort();
        }
        return url.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }
}
