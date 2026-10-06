package io.github.ricardoord.opswatch.incident.domain;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/**
 * Which incidents of an organization a listing wants (docs/api/endpoints-v1.md#incidentes-incident). Incidents of
 * deleted projects and monitors are included: they are history.
 *
 * @param statuses only these; empty for every status
 * @param projectId only those of this project; null for every project
 * @param monitorId only those of this monitor; null for every monitor
 * @param from opened at or after it; null for no lower bound
 * @param to opened before it; null for no upper bound
 */
public record IncidentFilter(
        Set<IncidentStatus> statuses,
        @Nullable UUID projectId,
        @Nullable UUID monitorId,
        @Nullable Instant from,
        @Nullable Instant to) {

    public IncidentFilter {
        statuses = Set.copyOf(statuses);
    }

    public Specification<Incident> of(UUID organizationId) {
        return (root, query, criteria) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(criteria.equal(root.get("organizationId"), organizationId));
            if (!statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            if (projectId != null) {
                predicates.add(criteria.equal(root.get("projectId"), projectId));
            }
            if (monitorId != null) {
                predicates.add(criteria.equal(root.get("monitorId"), monitorId));
            }
            if (from != null) {
                predicates.add(criteria.greaterThanOrEqualTo(root.get("openedAt"), from));
            }
            if (to != null) {
                predicates.add(criteria.lessThan(root.get("openedAt"), to));
            }
            return criteria.and(predicates.toArray(Predicate[]::new));
        };
    }
}
