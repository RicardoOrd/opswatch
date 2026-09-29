package io.github.ricardoord.opswatch.organization;

import static io.github.ricardoord.opswatch.organization.Permission.CHANNEL_READ;
import static io.github.ricardoord.opswatch.organization.Permission.CHANNEL_WRITE;
import static io.github.ricardoord.opswatch.organization.Permission.INCIDENT_ACKNOWLEDGE;
import static io.github.ricardoord.opswatch.organization.Permission.INCIDENT_READ;
import static io.github.ricardoord.opswatch.organization.Permission.MEMBER_MANAGE_BASIC;
import static io.github.ricardoord.opswatch.organization.Permission.MEMBER_READ;
import static io.github.ricardoord.opswatch.organization.Permission.MONITOR_READ;
import static io.github.ricardoord.opswatch.organization.Permission.MONITOR_WRITE;
import static io.github.ricardoord.opswatch.organization.Permission.ORGANIZATION_READ;
import static io.github.ricardoord.opswatch.organization.Permission.ORGANIZATION_UPDATE;
import static io.github.ricardoord.opswatch.organization.Permission.PROJECT_READ;
import static io.github.ricardoord.opswatch.organization.Permission.PROJECT_WRITE;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The role of a member in one organization; nobody has a global role. The permissions of each role are fixed in code,
 * as in the matrix of docs/security/authorization-model.md#matriz-rbac: there are no configurable roles in V1.
 */
public enum Role {
    OWNER(EnumSet.allOf(Permission.class)),
    ADMIN(EnumSet.of(
            ORGANIZATION_READ,
            ORGANIZATION_UPDATE,
            MEMBER_READ,
            MEMBER_MANAGE_BASIC,
            PROJECT_READ,
            PROJECT_WRITE,
            MONITOR_READ,
            MONITOR_WRITE,
            INCIDENT_READ,
            INCIDENT_ACKNOWLEDGE,
            CHANNEL_READ,
            CHANNEL_WRITE)),
    MEMBER(EnumSet.of(
            ORGANIZATION_READ,
            MEMBER_READ,
            PROJECT_READ,
            MONITOR_READ,
            MONITOR_WRITE,
            INCIDENT_READ,
            INCIDENT_ACKNOWLEDGE,
            CHANNEL_READ)),
    VIEWER(EnumSet.of(ORGANIZATION_READ, MEMBER_READ, PROJECT_READ, MONITOR_READ, INCIDENT_READ));

    private final Set<Permission> permissions;

    Role(EnumSet<Permission> permissions) {
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    public boolean grants(Permission permission) {
        return permissions.contains(permission);
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
