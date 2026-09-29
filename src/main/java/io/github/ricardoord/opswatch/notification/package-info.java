/** Notification channels (email, webhook) and reliable delivery with retries, driven by incident events. */
@ApplicationModule(
        displayName = "Notification",
        allowedDependencies = {"incident", "organization", "egress", "shared"})
@NullMarked
package io.github.ricardoord.opswatch.notification;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
