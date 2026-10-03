package io.github.ricardoord.opswatch.monitoring.application;

import io.github.ricardoord.opswatch.egress.HeaderPolicy;
import io.github.ricardoord.opswatch.egress.RequestHeader;
import io.github.ricardoord.opswatch.egress.TargetKind;
import io.github.ricardoord.opswatch.egress.TargetPolicy;
import io.github.ricardoord.opswatch.monitoring.MonitorDeleted;
import io.github.ricardoord.opswatch.monitoring.MonitorPaused;
import io.github.ricardoord.opswatch.monitoring.domain.Monitor;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorRepository;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorSettings;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorState;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStateRepository;
import io.github.ricardoord.opswatch.monitoring.domain.MonitorStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
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
    private final MonitorHeaders monitorHeaders;
    private final MonitorLimits limits;
    private final ApplicationEventPublisher events;
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
            MonitorHeaders monitorHeaders,
            MonitorLimits limits,
            ApplicationEventPublisher events,
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
        this.monitorHeaders = monitorHeaders;
        this.limits = limits;
        this.events = events;
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
     * @param headers stored encrypted ({@link MonitorHeaders}); empty for none
     * @throws InvalidFieldException if the settings break an invariant, or a header breaks {@link HeaderPolicy} (400)
     * @throws TargetNotAllowedException if {@link TargetPolicy} rejects the URL (422)
     * @throws ConflictException if the project already has a monitor with that name, whatever its case (409)
     * @throws QuotaExceededException if the organization has as many monitors as allowed (422)
     */
    public MonitorView create(
            UUID userId,
            UUID projectId,
            String name,
            String url,
            SettingsChanges changes,
            List<RequestHeader> headers) {
        access.requireForProject(userId, projectId, Permission.MONITOR_WRITE);
        MonitorSettings settings = changes.applyTo(MonitorSettings.DEFAULTS);
        requireAllowed(headers);
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
            UUID id = ids.next();
            Monitor monitor = Monitor.create(
                    id, project, name, target, settings, monitorHeaders.seal(id, headers), userId, clock);
            if (monitors.existsActiveName(project.id(), monitor.name())) {
                throw new ConflictException(NAME_TAKEN);
            }
            monitors.save(monitor);
            MonitorState state =
                    states.save(MonitorState.pending(monitor.id(), jitter.next(settings.intervalSeconds()), clock));
            // Assigns the version for the ETag of the response
            flushTranslatingDuplicateName();
            return MonitorView.of(monitor, state, headers);
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
    public Page<MonitorView> listOf(
            UUID userId, UUID projectId, @Nullable MonitorStatus status, @Nullable String query, Pageable pageable) {
        ProjectRef project = access.requireForProject(userId, projectId, Permission.MONITOR_READ);
        return monitors.findActiveOfProject(
                        project.id(),
                        status == null ? EnumSet.allOf(MonitorStatus.class) : EnumSet.of(status),
                        namePattern(query),
                        pageable)
                .map(found -> view(found.monitor(), found.state()));
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
    public MonitorView get(UUID userId, UUID monitorId) {
        Monitor monitor = authorized(userId, monitorId, Permission.MONITOR_READ);
        return view(monitor, stateOf(monitor));
    }

    /**
     * The same steps as {@link #create}: authorize, check a URL that is sent outside any transaction, and in the
     * transaction authorize again and compare {@code If-Match}. A shorter interval takes effect at once
     * ({@link MonitorState#intervalChanged}), with the state row locked: a pause at the same time would otherwise end
     * {@code PAUSED} with a next check, which the database rejects.
     *
     * @param name null to keep it
     * @param url null to keep it
     * @param headers null to keep them; otherwise the whole new list, empty for none
     * @param ifMatch the {@code If-Match} header, if the client sent one
     * @throws InvalidFieldException if the resulting settings break an invariant, or a header breaks
     *     {@link HeaderPolicy} (400)
     * @throws TargetNotAllowedException if {@link TargetPolicy} rejects the new URL (422)
     * @throws ConflictException if another monitor of the project has the new name (409)
     * @throws PreconditionFailedException if {@code ifMatch} does not match the current version (412)
     */
    public MonitorView update(
            UUID userId,
            UUID monitorId,
            @Nullable String name,
            @Nullable String url,
            SettingsChanges changes,
            @Nullable List<RequestHeader> headers,
            @Nullable String ifMatch) {
        authorized(userId, monitorId, Permission.MONITOR_WRITE);
        if (headers != null) {
            requireAllowed(headers);
        }
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
            List<RequestHeader> current = monitorHeaders.unseal(monitor);
            // A new ciphertext would differ every time: only re-encrypt what really changed
            if (headers != null && !headers.equals(current)) {
                monitor.replaceHeaders(monitorHeaders.seal(monitor.id(), headers), clock);
                current = headers;
            }
            // Fails here on a concurrent change (@Version) or a taken name, and gives the response its new version
            flushTranslatingDuplicateName();
            if (rescheduled) {
                state.intervalChanged(settings.intervalSeconds(), clock);
            }
            return MonitorView.of(monitor, state, current);
        });
    }

    /**
     * Stops checking it until it is resumed, and publishes {@link MonitorPaused} in the same transaction.
     *
     * @throws ConflictException if it is already paused (409)
     */
    public MonitorView pause(UUID userId, UUID monitorId) {
        authorized(userId, monitorId, Permission.MONITOR_WRITE);
        return inTransaction(() -> {
            MonitorState state = lockedStateOf(monitorId);
            Monitor monitor = authorized(userId, monitorId, Permission.MONITOR_WRITE);
            state.pause(clock);
            events.publishEvent(new MonitorPaused(
                    monitor.id(), monitor.organizationId(), monitor.projectId(), clock.instant(), userId));
            return view(monitor, state);
        });
    }

    /**
     * Checks it again, {@code PENDING} and with its first check a random while away, as a new monitor.
     *
     * @throws ConflictException if it is not paused (409)
     */
    public MonitorView resume(UUID userId, UUID monitorId) {
        authorized(userId, monitorId, Permission.MONITOR_WRITE);
        return inTransaction(() -> {
            MonitorState state = lockedStateOf(monitorId);
            Monitor monitor = authorized(userId, monitorId, Permission.MONITOR_WRITE);
            state.resume(jitter.next(monitor.settings().intervalSeconds()), clock);
            return view(monitor, state);
        });
    }

    /** Logical, with the state as on a pause; publishes {@link MonitorDeleted} in the same transaction. */
    public void delete(UUID userId, UUID monitorId) {
        authorized(userId, monitorId, Permission.MONITOR_WRITE);
        transactions.executeWithoutResult(status -> {
            MonitorState state = lockedStateOf(monitorId);
            Monitor monitor =
                    authorize(userId, monitorId, Permission.MONITOR_WRITE, monitors.findActiveByIdForUpdate(monitorId));
            delete(monitor, state, userId);
        });
    }

    /**
     * Every monitor of a deleted project, one by one through the same steps as {@link #delete(UUID, UUID)}, in the
     * caller's transaction: each one publishes its {@link MonitorDeleted} and goes up a version. Never a bulk
     * {@code UPDATE}: without the new version, a {@code PATCH} that read a monitor before would write it back alive.
     * Only monitors not deleted yet, so a repeated call does nothing.
     *
     * @param deletedBy who deleted the project
     */
    void deleteAllOf(UUID projectId, UUID deletedBy) {
        for (UUID monitorId : monitors.findActiveIdsOfProject(projectId)) {
            MonitorState state = lockedStateOf(monitorId);
            // Deleted meanwhile by someone else: nothing left to do
            monitors.findActiveByIdForUpdate(monitorId).ifPresent(monitor -> delete(monitor, state, deletedBy));
        }
    }

    /**
     * The state row was locked first and the monitor's second, the order of every writer of both. The monitor was read
     * locked, so its version is the last committed one.
     */
    private void delete(Monitor monitor, MonitorState state, UUID deletedBy) {
        monitor.delete(clock);
        state.stop(clock);
        events.publishEvent(new MonitorDeleted(
                monitor.id(), monitor.organizationId(), monitor.projectId(), clock.instant(), deletedBy));
    }

    /**
     * {@code FOR UPDATE}, before reading the monitor in the same transaction: whoever was changing either of them has
     * committed by then, and the monitor is read as it was left.
     */
    private MonitorState lockedStateOf(UUID monitorId) {
        return states.findByIdForUpdate(monitorId)
                .orElseThrow(() -> new ResourceNotFoundException("monitor", monitorId));
    }

    /**
     * Authorized on its project, which must not be deleted either. A non-member gets the 404 of a missing monitor,
     * never one that names its project.
     */
    private Monitor authorized(UUID userId, UUID monitorId, Permission permission) {
        return authorize(userId, monitorId, permission, monitors.findByIdAndDeletedAtIsNull(monitorId));
    }

    private Monitor authorize(UUID userId, UUID monitorId, Permission permission, Optional<Monitor> found) {
        Monitor monitor = found.orElseThrow(() -> new ResourceNotFoundException("monitor", monitorId));
        try {
            access.requireForProject(userId, monitor.projectId(), permission);
        } catch (ResourceNotFoundException ex) {
            throw new ResourceNotFoundException("monitor", monitorId);
        }
        return monitor;
    }

    private MonitorView view(Monitor monitor, MonitorState state) {
        return MonitorView.of(monitor, state, monitorHeaders.unseal(monitor));
    }

    /** The field of the error points at the header and its part: {@code headers[2].name}. Never quotes a value. */
    private static void requireAllowed(List<RequestHeader> headers) {
        HeaderPolicy.check(headers).ifPresent(violation -> {
            throw new InvalidFieldException(violation.field("headers"), violation.code(), violation.message());
        });
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
