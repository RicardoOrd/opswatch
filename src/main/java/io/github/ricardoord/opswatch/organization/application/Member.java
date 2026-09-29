package io.github.ricardoord.opswatch.organization.application;

import io.github.ricardoord.opswatch.identity.UserSummary;
import io.github.ricardoord.opswatch.organization.domain.Membership;

/** A membership with the user it belongs to, as the member endpoints show it. */
public record Member(Membership membership, UserSummary user) {}
