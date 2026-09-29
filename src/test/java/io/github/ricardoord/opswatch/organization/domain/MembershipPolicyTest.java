package io.github.ricardoord.opswatch.organization.domain;

import static io.github.ricardoord.opswatch.organization.Permission.MEMBER_MANAGE_BASIC;
import static io.github.ricardoord.opswatch.organization.Permission.MEMBER_MANAGE_PRIVILEGED;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ricardoord.opswatch.organization.Permission;
import io.github.ricardoord.opswatch.organization.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The rules of docs/security/authorization-model.md#reglas-que-la-matriz-no-expresa, one by one. */
class MembershipPolicyTest {

    @ParameterizedTest(name = "{0} → {1} needs {2}")
    @CsvSource({
        // Between MEMBER and VIEWER: what an ADMIN may do
        "VIEWER, MEMBER, MEMBER_MANAGE_BASIC",
        "MEMBER, VIEWER, MEMBER_MANAGE_BASIC",
        "MEMBER, MEMBER, MEMBER_MANAGE_BASIC",
        // Anything that touches ADMIN or OWNER, before or after: only an OWNER
        "MEMBER, ADMIN,  MEMBER_MANAGE_PRIVILEGED",
        "ADMIN,  MEMBER, MEMBER_MANAGE_PRIVILEGED",
        "ADMIN,  ADMIN,  MEMBER_MANAGE_PRIVILEGED",
        "ADMIN,  OWNER,  MEMBER_MANAGE_PRIVILEGED",
        "OWNER,  VIEWER, MEMBER_MANAGE_PRIVILEGED"
    })
    void changingARoleNeedsThePermissionOfTheHighestRoleInvolved(Role from, Role to, Permission needed) {
        assertThat(MembershipPolicy.toManage(from, to)).isEqualTo(needed);
    }

    @Test
    void addingOrRemovingNeedsThePermissionOfThatRole() {
        assertThat(MembershipPolicy.toManage(Role.VIEWER)).isEqualTo(MEMBER_MANAGE_BASIC);
        assertThat(MembershipPolicy.toManage(Role.MEMBER)).isEqualTo(MEMBER_MANAGE_BASIC);
        assertThat(MembershipPolicy.toManage(Role.ADMIN)).isEqualTo(MEMBER_MANAGE_PRIVILEGED);
        assertThat(MembershipPolicy.toManage(Role.OWNER)).isEqualTo(MEMBER_MANAGE_PRIVILEGED);
    }

    @ParameterizedTest(name = "on oneself {0} → {1}: {2}")
    @CsvSource({
        "ADMIN,  OWNER,  true",
        "VIEWER, MEMBER, true",
        "MEMBER, ADMIN,  true",
        "OWNER,  ADMIN,  false",
        "ADMIN,  ADMIN,  false",
        "MEMBER, VIEWER, false"
    })
    void nobodyRaisesTheirOwnRole(Role from, Role to, boolean promotion) {
        assertThat(MembershipPolicy.isSelfPromotion(true, from, to)).isEqualTo(promotion);
        // On someone else, raising is a matter of permissions, not of this rule
        assertThat(MembershipPolicy.isSelfPromotion(false, from, to)).isFalse();
    }

    @Test
    void theLastOwnerCannotStopBeingOne() {
        assertThat(MembershipPolicy.removesLastOwner(Role.OWNER, Role.ADMIN, 1)).isTrue();
        assertThat(MembershipPolicy.removesLastOwner(Role.OWNER, null, 1)).isTrue();
        assertThat(MembershipPolicy.removesLastOwner(Role.OWNER, Role.ADMIN, 2)).isFalse();
        assertThat(MembershipPolicy.removesLastOwner(Role.OWNER, Role.OWNER, 1)).isFalse();
        assertThat(MembershipPolicy.removesLastOwner(Role.ADMIN, null, 1)).isFalse();
    }
}
