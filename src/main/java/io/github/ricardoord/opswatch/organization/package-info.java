/** Organizations (tenants), memberships, roles, projects and access control decisions. */
@ApplicationModule(
        displayName = "Organization",
        allowedDependencies = {"identity", "shared"})
@NullMarked
package io.github.ricardoord.opswatch.organization;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
