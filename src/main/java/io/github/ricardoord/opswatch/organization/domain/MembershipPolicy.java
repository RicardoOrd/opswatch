package io.github.ricardoord.opswatch.organization.domain;

import io.github.ricardoord.opswatch.organization.Permission;
import io.github.ricardoord.opswatch.organization.Role;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * The rules of member management that the permission matrix does not express
 * (docs/security/authorization-model.md#reglas-que-la-matriz-no-expresa).
 */
public final class MembershipPolicy {

    private MembershipPolicy() {}

    /**
     * What it takes to add, change or remove a member: touching {@code OWNER} or {@code ADMIN}, before or after the
     * change, needs {@link Permission#MEMBER_MANAGE_PRIVILEGED}; the rest, {@link Permission#MEMBER_MANAGE_BASIC}. So an
     * {@code ADMIN} can neither assign {@code ADMIN} nor touch another {@code ADMIN} or an {@code OWNER}.
     *
     * @param roles the member's role before the change and after it, or the only one when adding or removing
     */
    public static Permission toManage(Role... roles) {
        return Arrays.stream(roles).anyMatch(Role::isPrivileged)
                ? Permission.MEMBER_MANAGE_PRIVILEGED
                : Permission.MEMBER_MANAGE_BASIC;
    }

    /**
     * Nobody raises their own role, whatever permissions they hold: a change on oneself can only go down. Going down
     * needs no permission, just as leaving does not.
     */
    public static boolean isSelfPromotion(boolean onOneself, Role from, Role to) {
        return onOneself && to.outranks(from);
    }

    /**
     * Whether the change leaves the organization without an {@code OWNER}.
     *
     * @param to the new role, or null when the member leaves or is removed
     * @param owners the organization's {@code OWNER} count before the change
     */
    public static boolean removesLastOwner(Role from, @Nullable Role to, long owners) {
        return from == Role.OWNER && to != Role.OWNER && owners <= 1;
    }
}
