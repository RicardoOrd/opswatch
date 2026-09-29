package io.github.ricardoord.opswatch.organization.web;

import io.github.ricardoord.opswatch.organization.Role;
import io.github.ricardoord.opswatch.organization.application.Member;
import java.time.Instant;
import java.util.UUID;

/**
 * A member of an organization. {@code version} is the value of its {@code ETag}, for {@code If-Match}.
 *
 * @param joinedAt when the membership was created
 */
public record MemberResponse(UUID userId, String email, String displayName, Role role, Instant joinedAt, long version) {

    static MemberResponse from(Member member) {
        var membership = member.membership();
        return new MemberResponse(
                membership.userId(),
                member.user().email(),
                member.user().displayName(),
                membership.role(),
                membership.createdAt(),
                membership.savedVersion());
    }
}
