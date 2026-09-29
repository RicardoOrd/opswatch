package io.github.ricardoord.opswatch.identity.domain;

/** Whether the user may sign in. A disabled user keeps their data but gets no new tokens. */
public enum UserStatus {
    ACTIVE,
    DISABLED
}
