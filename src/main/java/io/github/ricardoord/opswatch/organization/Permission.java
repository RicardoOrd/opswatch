package io.github.ricardoord.opswatch.organization;

/** What a member may do in an organization. Which roles have each: {@link Role} and docs/security/authorization-model.md. */
public enum Permission {
    ORGANIZATION_READ,
    ORGANIZATION_UPDATE,
    ORGANIZATION_DELETE,
    MEMBER_READ,
    /** Add, remove and switch members between {@code MEMBER} and {@code VIEWER}. */
    MEMBER_MANAGE_BASIC,
    /** Add, remove or assign {@code ADMIN} and {@code OWNER}. */
    MEMBER_MANAGE_PRIVILEGED,
    PROJECT_READ,
    PROJECT_WRITE,
    /** Never the values of the monitors' headers: nobody reads them back. */
    MONITOR_READ,
    MONITOR_WRITE,
    INCIDENT_READ,
    INCIDENT_ACKNOWLEDGE,
    /** With recipients and URLs masked. */
    CHANNEL_READ,
    CHANNEL_WRITE
}
