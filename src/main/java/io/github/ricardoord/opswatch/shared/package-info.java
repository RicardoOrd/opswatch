/**
 * Small technical kernel shared by every module: error model, pagination, current user, clock and secret
 * encryption. It must never contain business rules.
 */
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)
@NullMarked
package io.github.ricardoord.opswatch.shared;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
