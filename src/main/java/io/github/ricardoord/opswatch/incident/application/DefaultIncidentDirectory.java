package io.github.ricardoord.opswatch.incident.application;

import io.github.ricardoord.opswatch.incident.IncidentDirectory;
import io.github.ricardoord.opswatch.incident.IncidentSummary;
import io.github.ricardoord.opswatch.incident.domain.Incident;
import io.github.ricardoord.opswatch.incident.domain.IncidentRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultIncidentDirectory implements IncidentDirectory {

    private final IncidentRepository incidents;

    DefaultIncidentDirectory(IncidentRepository incidents) {
        this.incidents = incidents;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IncidentSummary> findById(UUID id) {
        return incidents.findById(id).map(DefaultIncidentDirectory::summary);
    }

    private static IncidentSummary summary(Incident incident) {
        return new IncidentSummary(
                incident.id(),
                incident.organizationId(),
                incident.projectId(),
                incident.monitorId(),
                incident.monitorName(),
                incident.status().name(),
                incident.cause(),
                incident.causeHttpStatus(),
                incident.openedAt(),
                incident.resolvedAt(),
                incident.resolution());
    }
}
