package io.github.ricardoord.opswatch.organization.domain;

import io.github.ricardoord.opswatch.organization.Role;

/** An organization as one of its members sees it: with that member's role. */
public record OrganizationWithRole(Organization organization, Role role) {}
