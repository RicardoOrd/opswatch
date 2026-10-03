package io.github.ricardoord.opswatch.monitoring.application;

import io.github.ricardoord.opswatch.egress.TargetKind;
import io.github.ricardoord.opswatch.egress.TargetPolicy;
import io.github.ricardoord.opswatch.monitoring.domain.Monitor;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorRepository;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSettings;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorState;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStateRepository;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStatus;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorWithState;
import io.github.ricardoord.opswatch.monitoring.domain.StatusCount;
import io.github.ricardoord.opswatch.organization.AccessControl;
import io.github.ricardoord.opswatch.organization.Permission;
import io.github.ricardoord.opswatch.organization.ProjectDirectory;
import io.github.ricardoord.opswatch.organization.ProjectRef;
import io.github.ricardoord.opswatch.shared.error.ConflictException;
import io.github.ricardoord.opswatch.shared.error.InvalidFieldException;
import io.github.ricardoord.opswatch.shared.error.InvalidParameterException;
import io.github.ricardoord.opswatch.shared.error.PreconditionFailedException;
import io.github.ricardoord.opswatch.shared.error.QuotaExceededException;
import io.github.ricardoord.opswatch.shared.error.ResourceNotFoundException;
import io.github.ricardoord.opswatch.shared.error.TargetNotAllowedException;
import io.github.ricardoord.opswatch.shared.id.IdGenerator;
import io.github.ricardoord.opswatch.shared.lock.AdvisoryLocks;
import io.github.ricardoord.opswatch.shared.lock.LockSpace;
import io.github.ricardoord.opswatch.shared.web.ETags;
import java.net.URI;
import java.time.Clock;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Monitors as the members of their organization see them. Every use case authorizes on the project or the monitor it
 * loaded: the project and the organization never come from data the client chose.
 *
 * <p>Saving a URL resolves its host ({@link TargetPolicy}), which is external I/O whose speed the user chooses. It runs
 * outside any transaction, so a slow DNS never holds a connection of the pool, and only after authorizing, so that
 * someone without the permission cannot make the server resolve names.
 */
@Service
@EnableConfigurationProperties(MonitorLimits.class)
public class MonitorService {

    static final String NAME_TAKEN = "The project already has a monitor with this name.";
    static final int QUERY_MAX_LENGTH = 100;

    private final MonitorRepository monitors;
    private final MonitorStateRepository states;
    private final AccessControl access;
    private final ProjectDirectory projects;
    private final TargetPolicy targets;
    private final AdvisoryLocks locks;
    private final IdGenerator ids;
    private final InitialJitter jitter;
    private final MonitorLimits limits;
    private final TransactionOperations transactions;
    private final Clock clock;

    public MonitorService(
            MonitorRepository monitors,
            MonitorStateRepository states,
            AccessControl access,
            ProjectDirectory projects,
            TargetPolicy targets,
            AdvisoryLocks locks,
            IdGenerator ids,
            InitialJitter jitter,
            MonitorLimits limits,
            TransactionOperations transactions,
            Clock clock) {
        this.monitors = monitors;
        this.states = states;
        this.access = access;
        this.projects = projects;
        this.targets = targets;
        this.locks = locks;
        this.ids = ids;
        this.jitter = jitter;
        this.limits = limits;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * A new monitor, {@code PENDING}, with its first check a random while away ({@link InitialJitter}). The insert runs
     * in a short transaction that authorizes again and holds the project ({@link ProjectDirectory#lockActive}), so a
     * monitor is never born in a project whose deletion has gone through. Creations in one organization take turns on
     * an advisory lock for the quota.
     *
     * @param changes the settings sent; the rest take {@link MonitorSettings#DEFAULTS}
     * @throws InvalidFieldException if the settings break an invariant (400)
     * @throws TargetNotAllowedException if {@link TargetPolicy} rejects the URL (422)
     * @throws ConflictException if the project already has a monitor with that name, whatever its case (409)
     * @throws QuotaExceededException if the organization has as many monitors as allowed (422)
     */
    public MonitorWithState create(UUID userId, UUID projectId, String name, String url, SettingsChanges changes) {
        access.requireForProject(userId, projectId, Permission.MONITOR_WRITE);
        MonitorSettings settings = changes.applyTo(MonitorSettings.DEFAULTS);
        URI target = targets.validate(url, TargetKind.MONITOR);
        return inTransaction(() -> {
            access.requireForProject(userId, projectId, Permission.MONITOR_WRITE);
            ProjectRef project = projects.lockActive(projectId);
            locks.lock(LockSpace.MONITORS_OF_ORGANIZATION, project.organizationId());
            int limit = limits.monitorsPerOrganization();
            if (monitors.countByOrganizationIdAndDeletedAtIsNull(project.organizationId()) >= limit) {
                throw new QuotaExceededException(
                        "The organization already has " + limit + " monitors, the most allowed.");
            }
            Monitor monitor = Monitor.create(ids.next(), project, name, target, settings, userId, clock);
            if (monitors.existsActiveName(project.id(), monitor.name())) {
                throw new ConflictException(NAME_TAKEN);
            }
            monitors.save(monitor);
            MonitorState state =
                    states.save(MonitorState.pending(monitor.id(), jitter.next(settings.intervalSeconds()), clock));
            // Assigns the version for the ETag of the response
            flushTranslatingDuplicateName();
            return new MonitorWithState(monitor, state);
        });
    }

    /**
     * Only the monitors of a project in an organization the user is a member of.
     *
     * @param status null for every status
     * @param query part of the name, whatever its case, taken literally; null or blank for every name
     * @throws InvalidParameterException if {@code query} is longer than {@value #QUERY_MAX_LENGTH} characters (400)
     */
    @Transactional(readOnly = true)
    public Page<MonitorWithState> listOf(
            UUID userId, UUID projectId, @Nullable MonitorStatus status, @Nullable String query, Pageable pageable) {
        ProjectRef project = access.requireForProject(userId, projectId, Permission.MONITOR_READ);
        return monitors.findActiveOfProject(
                project.id(),
                status == null ? EnumSet.allOf(MonitorStatus.class) : EnumSet.of(status),
                namePattern(query),
                pageable);
    }

    /** The monitors of a project by status, with every status even when it has none. */
    @Transactional(readOnly = true)
    public Map<MonitorStatus, Long> summaryOf(UUID userId, UUID projectId) {
        ProjectRef project = access.requireForProject(userId, projectId, Permission.MONITOR_READ);
        Map<MonitorStatus, Long> counts = new EnumMap<>(MonitorStatus.class);
        for (MonitorStatus status : MonitorStatus.values()) {
            counts.put(status, 0L);
        }
        for (StatusCount count : monitors.countActiveByStatus(project.id())) {
            counts.put(count.status(), count.count());
        }
        return Collections.unmodifiableMap(counts);
    }

    @Transactional(readOnly = true)
    public MonitorWithState get(UUID userId, UUID monitorId) {
        Monitor monitor = authorized(userId, monitorId, Permission.MONITOR_READ);
        return new MonitorWithState(monitor, stateOf(monitor));
    }

    /**
     * The same steps as {@link #create}: authorize, check a URL that is sent outside any transaction, and in the
     * transaction authorize again and compare {@code If-Match}. A shorter interval takes effect at once
     * ({@link MonitorState#intervalChanged}), with the state row locked: a pause at the same time would otherwise end
     * {@code PAUSED} with a next check, which the database rejects.
     *
     * @param name null to keep it
     * @param url null to keep it
     * @param ifMatch the {@code If-Match} header, if the client sent one
     * @throws InvalidFieldException if the resulting settings break an invariant (400)
     * @throws TargetNotAllowedException if {@link TargetPolicy} rejects the new URL (422)
     * @throws ConflictException if another monitor of the project has the new name (409)
     * @throws PreconditionFailedException if {@code ifMatch} does not match the current version (412)
     */
    public MonitorWithState update(
            UUID userId,
            UUID monitorId,
            @Nullable String name,
            @Nullable String url,
            SettingsChanges changes,
            @Nullable String ifMatch) {
        authorized(userId, monitorId, Permission.MONITOR_WRITE);
        @Nullable URI target = url == null ? null : targets.validate(url, TargetKind.MONITOR);
        return inTransaction(() -> {
            Monitor monitor = authorized(userId, monitorId, Permission.MONITOR_WRITE);
            ETags.requireMatch(ifMatch, monitor.savedVersion());
            MonitorSettings settings = changes.applyTo(monitor.settings());
            boolean rescheduled =
                    settings.intervalSeconds() != monitor.settings().intervalSeconds();
            // The state row before the monitor's, the order every writer of both follows
            MonitorState state = rescheduled
                    ? states.findByIdForUpdate(monitor.id()).orElseThrow(() -> stateMissing(monitor))
                    : stateOf(monitor);
            if (name != null) {
                monitor.rename(name, clock);
            }
            if (target != null) {
                monitor.retarget(target, clock);
            }
            monitor.reconfigure(settings, clock);
            // Fails here on a concurrent change (@Version) or a taken name, and gives the response its new version
            flushTranslatingDuplicateName();
            if (rescheduled) {
                state.intervalChanged(settings.intervalSeconds(), clock);
            }
            return new MonitorWithState(monitor, state);
        });
    }

    /**
     * Authorized on its project, which must not be deleted either. A non-member gets the 404 of a missing monitor,
     * never one that names its project.
     */
    private Monitor authorized(UUID userId, UUID monitorId, Permission permission) {
        Monitor monitor = monitors.findByIdAndDeletedAtIsNull(monitorId)
                .orElseThrow(() -> new ResourceNotFoundException("monitor", monitorId));
        try {
            access.requireForProject(userId, monitor.projectId(), permission);
        } catch (ResourceNotFoundException ex) {
            throw new ResourceNotFoundException("monitor", monitorId);
        }
        return monitor;
    }

    private MonitorState stateOf(Monitor monitor) {
        return states.findById(monitor.id()).orElseThrow(() -> stateMissing(monitor));
    }

    /** Both rows are inserted in one transaction: a monitor without its state is a bug. */
    private static IllegalStateException stateMissing(Monitor monitor) {
        return new IllegalStateException(monitor + " has no state");
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }

    /**
     * {@code %}, {@code _} and the escape character are searched for literally: the user's text never becomes a
     * wildcard.
     */
    static String namePattern(@Nullable String query) {
        if (query == null || query.isBlank()) {
            return "%";
        }
        if (query.codePointCount(0, query.length()) > QUERY_MAX_LENGTH) {
            throw new InvalidParameterException("q is longer than " + QUERY_MAX_LENGTH + " characters.");
        }
        StringBuilder pattern = new StringBuilder("%");
        for (char character : query.toCharArray()) {
            if (character == '%' || character == '_' || character == MonitorRepository.LIKE_ESCAPE) {
                pattern.append(MonitorRepository.LIKE_ESCAPE);
            }
            pattern.append(character);
        }
        return pattern.append('%').toString();
    }

    /** The unique index settles what the check before saving cannot: a rename, or a creation in a race. */
    private void flushTranslatingDuplicateName() {
        try {
            monitors.flush();
        } catch (DataIntegrityViolationException ex) {
            if (MonitorRepository.UNIQUE_NAME_INDEX.equals(violatedConstraint(ex))) {
                throw new ConflictException(NAME_TAKEN);
            }
            throw ex;
        }
    }

    private static @Nullable String violatedConstraint(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }
}
