package io.github.ricardoord.opswatch.shared.lock;

/**
 * The spaces of the PostgreSQL advisory locks, all in one place: two locks in the same space with keys that hash
 * alike would wait for each other for no reason, so no number is ever used twice. A number never changes once
 * released: instances of two versions running side by side during a deployment must agree on it.
 */
public enum LockSpace {
    /** Organizations a user owns: the key is the user (OW-016). */
    ORGANIZATIONS_OWNED_BY_USER(1),
    /** Monitors of an organization: the key is the organization (OW-021). */
    MONITORS_OF_ORGANIZATION(2),
    /** Notification channels of an organization: the key is the organization (OW-035). */
    CHANNELS_OF_ORGANIZATION(3);

    private final int number;

    LockSpace(int number) {
        this.number = number;
    }

    /** The first key of the two-key form of PostgreSQL's advisory locks. */
    public int number() {
        return number;
    }
}
