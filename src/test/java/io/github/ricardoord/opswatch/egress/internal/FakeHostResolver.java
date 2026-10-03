package io.github.ricardoord.opswatch.egress.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Answers from a table; any other name does not resolve. Remembers what it was asked. Safe to share between threads:
 * the integration tests share one through {@code TestHostResolver}.
 */
public final class FakeHostResolver implements HostResolver {

    private final Map<String, List<InetAddress>> answers = new ConcurrentHashMap<>();
    private final List<String> lookups = new CopyOnWriteArrayList<>();

    /** @param addresses IP literals */
    public FakeHostResolver with(String host, String... addresses) {
        answers.put(host, Arrays.stream(addresses).map(InetAddress::ofLiteral).toList());
        return this;
    }

    /** Every name asked, in order. */
    public List<String> lookups() {
        return List.copyOf(lookups);
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        lookups.add(host);
        List<InetAddress> addresses = answers.get(host);
        if (addresses == null) {
            throw new UnknownHostException(host);
        }
        return addresses;
    }
}
