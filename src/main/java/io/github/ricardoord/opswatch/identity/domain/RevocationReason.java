package io.github.ricardoord.opswatch.identity.domain;

/** Why a refresh token stopped being valid before it expired. */
public enum RevocationReason {
    /** Spent on its successor. Presenting it again means someone holds a copy. */
    ROTATED,
    LOGOUT,
    /** A rotated token came back: the whole family is revoked. */
    REUSE_DETECTED,
    PASSWORD_CHANGED,
    USER_DISABLED
}
