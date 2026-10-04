package io.github.ricardoord.opswatch.egress;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;

/**
 * The only way to build an HTTP client for user-supplied URLs (layers 2 to 5 of
 * docs/security/ssrf-protection.md). A build rule keeps any other module from building one.
 *
 * <p>Every client it builds:
 *
 * <ul>
 *   <li>resolves names through the guarded resolver, which rejects a host if any of its addresses is blocked and
 *       connects only to the addresses it validated (layer 2, against DNS rebinding);
 *   <li>checks each request before it leaves: the URL against the rules of layer 1 that need no DNS (scheme, host form,
 *       port, credentials) and the headers against {@link HeaderPolicy} (layer 4). A URL or a header saved before a
 *       rule existed, or written to the database outside the API, never leaves;
 *   <li>ignores the proxy of the environment, never retries, never follows redirects (the caller follows them, and each
 *       hop is a new request that goes through the same checks), keeps no cookies, does not ask for compression and
 *       reuses no connection;
 *   <li>refuses response headers longer than 8 KiB per line or more than 100 in number.
 * </ul>
 *
 * <p>A blocked request throws {@link BlockedTargetException} from {@code execute}. A scheme other than http and https,
 * or credentials in the URL, the client itself refuses before any of that, with a {@code ClientProtocolException}:
 * those do not leave either.
 */
public interface EgressHttpClients {

    /** A new client. It is thread-safe: the caller builds one per purpose and closes it on shutdown. */
    CloseableHttpClient create(EgressClientSettings settings);
}
