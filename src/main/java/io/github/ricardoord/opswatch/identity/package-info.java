/** Users, credentials, login, JWT access tokens and rotating refresh tokens. */
@ApplicationModule(
        displayName = "Identity",
        allowedDependencies = {"shared"})
@NullMarked
package io.github.ricardoord.opswatch.identity;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
