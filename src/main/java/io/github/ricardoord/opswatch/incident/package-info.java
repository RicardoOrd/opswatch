/** Incident lifecycle driven by monitoring events, acknowledgement and timeline. */
@ApplicationModule(
        displayName = "Incident",
        allowedDependencies = {"monitoring", "organization", "shared"})
@NullMarked
package io.github.ricardoord.opswatch.incident;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
