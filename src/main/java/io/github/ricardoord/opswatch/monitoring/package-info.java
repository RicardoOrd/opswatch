/** Monitor configuration, check scheduling and execution, monitor state, check history and uptime. */
@ApplicationModule(
        displayName = "Monitoring",
        allowedDependencies = {"organization", "egress", "shared"})
@NullMarked
package io.github.ricardoord.opswatch.monitoring;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
