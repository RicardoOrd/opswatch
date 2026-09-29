package io.github.ricardoord.opswatch.organization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The matrix of docs/security/authorization-model.md#matriz-rbac, copied as data. A change to the code that does not
 * change the document, or the other way round, fails here.
 */
class RoleTest {

    @ParameterizedTest(name = "{0}: OWNER {1}, ADMIN {2}, MEMBER {3}, VIEWER {4}")
    @CsvSource({
        "ORGANIZATION_READ,        true, true,  true,  true",
        "ORGANIZATION_UPDATE,      true, true,  false, false",
        "ORGANIZATION_DELETE,      true, false, false, false",
        "MEMBER_READ,              true, true,  true,  true",
        "MEMBER_MANAGE_BASIC,      true, true,  false, false",
        "MEMBER_MANAGE_PRIVILEGED, true, false, false, false",
        "PROJECT_READ,             true, true,  true,  true",
        "PROJECT_WRITE,            true, true,  false, false",
        "MONITOR_READ,             true, true,  true,  true",
        "MONITOR_WRITE,            true, true,  true,  false",
        "INCIDENT_READ,            true, true,  true,  true",
        "INCIDENT_ACKNOWLEDGE,     true, true,  true,  false",
        "CHANNEL_READ,             true, true,  true,  false",
        "CHANNEL_WRITE,            true, true,  false, false"
    })
    void grantsWhatTheMatrixSays(Permission permission, boolean owner, boolean admin, boolean member, boolean viewer) {
        assertThat(Role.OWNER.grants(permission)).isEqualTo(owner);
        assertThat(Role.ADMIN.grants(permission)).isEqualTo(admin);
        assertThat(Role.MEMBER.grants(permission)).isEqualTo(member);
        assertThat(Role.VIEWER.grants(permission)).isEqualTo(viewer);
    }

    @Test
    void theMatrixCoversEveryPermission() {
        // A new permission without its row above would go untested
        Set<String> inTheMatrix = Set.of(
                "ORGANIZATION_READ",
                "ORGANIZATION_UPDATE",
                "ORGANIZATION_DELETE",
                "MEMBER_READ",
                "MEMBER_MANAGE_BASIC",
                "MEMBER_MANAGE_PRIVILEGED",
                "PROJECT_READ",
                "PROJECT_WRITE",
                "MONITOR_READ",
                "MONITOR_WRITE",
                "INCIDENT_READ",
                "INCIDENT_ACKNOWLEDGE",
                "CHANNEL_READ",
                "CHANNEL_WRITE");

        assertThat(Arrays.stream(Permission.values()).map(Enum::name).collect(Collectors.toSet()))
                .isEqualTo(inTheMatrix);
    }

    @Test
    void eachRoleHasEverythingTheOneBelowHas() {
        assertThat(Role.OWNER.permissions()).isEqualTo(EnumSet.allOf(Permission.class));
        assertThat(Role.OWNER.permissions()).containsAll(Role.ADMIN.permissions());
        assertThat(Role.ADMIN.permissions()).containsAll(Role.MEMBER.permissions());
        assertThat(Role.MEMBER.permissions()).containsAll(Role.VIEWER.permissions());
    }
}
