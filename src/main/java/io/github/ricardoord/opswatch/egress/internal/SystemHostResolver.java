package io.github.ricardoord.opswatch.egress.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.stereotype.Component;

/** The resolver of the operating system, with no deadline of its own: whoever calls it sets one. */
@Component
class SystemHostResolver implements HostResolver {

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        return List.of(InetAddress.getAllByName(host));
    }
}
