package io.github.ricardoord.opswatch.egress.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Host name to addresses. An interface of its own instead of calling {@link InetAddress} directly, so that tests
 * decide what a name resolves to and none depends on the real DNS.
 */
public interface HostResolver {

    /** @throws UnknownHostException if the name does not resolve */
    List<InetAddress> resolve(String host) throws UnknownHostException;
}
