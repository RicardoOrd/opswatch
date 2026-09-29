package io.github.ricardoord.opswatch.identity.security;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Origins allowed to use the refresh token cookie: the API's own (the origin of the token issuer) and the CORS list.
 * Defense in depth on top of {@code SameSite=Strict} against CSRF on refresh and logout (T-09).
 */
@Component
public class TrustedOrigins {

    private final Set<String> origins;

    public TrustedOrigins(JwtProperties jwt, CorsProperties cors) {
        Set<String> trusted = new LinkedHashSet<>();
        trusted.add(originOf(jwt.issuer()));
        cors.allowedOrigins().forEach(origin -> trusted.add(originOf(origin)));
        this.origins = Set.copyOf(trusted);
    }

    /**
     * Browsers always send {@code Origin} on a {@code POST}, so a missing one is rejected too: it only affects clients
     * that are not browsers, and they can add it.
     */
    public boolean allows(@Nullable String origin) {
        if (origin == null || origin.isBlank() || "null".equals(origin)) {
            return false;
        }
        try {
            return origins.contains(originOf(origin));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * {@code scheme://host[:port]} in lower case, without the default port or any path, as browsers serialize it.
     *
     * @throws IllegalArgumentException if the value is not an absolute http or https URL
     */
    static String originOf(String url) {
        URI uri = URI.create(url.strip());
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new IllegalArgumentException("Not an absolute URL: " + url);
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Not an http or https URL: " + url);
        }
        int port = uri.getPort();
        boolean defaultPort =
                port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }
}
