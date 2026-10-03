package io.github.ricardoord.opswatch.monitoring.application;

import io.github.ricardoord.opswatch.egress.RequestHeader;
import io.github.ricardoord.opswatch.monitoring.domain.Monitor;
import io.github.ricardoord.opswatch.shared.crypto.DecryptionFailedException;
import io.github.ricardoord.opswatch.shared.crypto.SecretCipher;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The headers of a monitor, encrypted at rest as JSON with {@code monitors.request_headers:<monitorId>} as associated
 * data (docs/security/security-architecture.md#cifrado-de-datos-sensibles-en-la-base-de-datos). Explicit here and not in
 * a JPA converter, which on reading would not know the id.
 */
@Component
class MonitorHeaders {

    static final String PURPOSE = "monitors.request_headers:";

    /** Its own mapper: what is sealed must not change with the settings of the web layer. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<List<RequestHeader>> LIST = new TypeReference<>() {};

    private final SecretCipher cipher;

    MonitorHeaders(SecretCipher cipher) {
        this.cipher = cipher;
    }

    /** @return null for no headers: nothing to encrypt */
    byte @Nullable [] seal(UUID monitorId, List<RequestHeader> headers) {
        if (headers.isEmpty()) {
            return null;
        }
        return cipher.encrypt(JSON.writeValueAsBytes(headers), PURPOSE + monitorId);
    }

    /** @throws DecryptionFailedException if they were not sealed for this monitor, or with a key no longer configured */
    List<RequestHeader> unseal(Monitor monitor) {
        byte[] sealed = monitor.requestHeaders();
        if (sealed == null) {
            return List.of();
        }
        return List.copyOf(JSON.readValue(cipher.decrypt(sealed, PURPOSE + monitor.id()), LIST));
    }
}
