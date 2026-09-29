package io.github.ricardoord.opswatch.identity;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** How other modules find users (docs/architecture/modules.md#identity). */
public interface UserDirectory {

    Optional<UserSummary> findById(UUID id);

    /** @param email as typed: case and surrounding spaces do not matter */
    Optional<UserSummary> findByEmail(String email);

    /** The users among {@code ids} that exist, by id. */
    Map<UUID, UserSummary> findAllById(Collection<UUID> ids);
}
