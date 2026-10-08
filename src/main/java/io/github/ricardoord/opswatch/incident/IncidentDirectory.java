package io.github.ricardoord.opswatch.incident;

import java.util.Optional;
import java.util.UUID;

/** How other modules read incidents (docs/architecture/modules.md#incident): {@code notification}, to tell of them. */
public interface IncidentDirectory {

    /** As it is now, resolved or not. Incidents are never deleted. */
    Optional<IncidentSummary> findById(UUID id);
}
