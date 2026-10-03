package io.github.ricardoord.opswatch.monitoring.domain;

import io.github.ricardoord.opswatch.organization.ProjectRef;
import io.github.ricardoord.opswatch.shared.text.VisibleText;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What to check, how often and what counts as correct (docs/architecture/domain-model.md#monitor). People edit it, with
 * optimistic locking; its execution state is {@link MonitorState}, in a table of its own because the engine writes it
 * on every check. It never leaves its project, and its organization is always its project's.
 */
@Entity
@Table(name = "monitors")
public class Monitor {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int URL_MAX_LENGTH = 2048;

    @Id
    private UUID id;

    private UUID organizationId;

    private UUID projectId;

    private String name;

    private String url;

    @Enumerated(EnumType.STRING)
    private ProbeMethod httpMethod;

    private short expectedStatusMin;

    private short expectedStatusMax;

    private int intervalSeconds;

    private int timeoutMs;

    private @Nullable Integer degradedThresholdMs;

    private boolean followRedirects;

    private short failureThreshold;

    private short recoveryThreshold;

    /** Null once that user's account is deleted. */
    private @Nullable UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    private @Nullable Instant deletedAt;

    /** Null until the first save: with the id assigned up front, this is how Spring Data knows the entity is new. */
    @Version
    private @Nullable Long version;

    /** For JPA, which populates the fields. */
    protected Monitor() {}

    private Monitor(
            UUID id, ProjectRef project, String name, URI url, MonitorSettings settings, UUID createdBy, Instant now) {
        this.id = id;
        this.organizationId = project.organizationId();
        this.projectId = project.id();
        this.name = name;
        this.url = url.toString();
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
        apply(settings);
    }

    /**
     * @param project where it goes, with its organization, as {@code organization} hands it over: never from the request
     * @param url already accepted by {@code TargetPolicy}, which normalizes it
     * @throws IllegalArgumentException if the name or the URL breaks an invariant (the request validation and
     *     {@code TargetPolicy} should have caught it)
     */
    public static Monitor create(
            UUID id, ProjectRef project, String name, URI url, MonitorSettings settings, UUID createdBy, Clock clock) {
        return new Monitor(id, project, validName(name), validUrl(url), settings, createdBy, now(clock));
    }

    /**
     * Unique in the project whatever the case: the database index has the last word.
     *
     * @throws IllegalArgumentException if the name breaks an invariant (the request validation should have caught it)
     */
    public void rename(String name, Clock clock) {
        String cleanName = validName(name);
        if (!cleanName.equals(this.name)) {
            this.name = cleanName;
            this.updatedAt = now(clock);
        }
    }

    /**
     * The state is not reset: the next checks correct it.
     *
     * @param url already accepted by {@code TargetPolicy}, which normalizes it
     */
    public void retarget(URI url, Clock clock) {
        String cleanUrl = validUrl(url).toString();
        if (!cleanUrl.equals(this.url)) {
            this.url = cleanUrl;
            this.updatedAt = now(clock);
        }
    }

    /** Replaces every setting at once: {@link MonitorSettings} has already checked them against each other. */
    public void reconfigure(MonitorSettings settings, Clock clock) {
        if (!settings.equals(settings())) {
            apply(settings);
            this.updatedAt = now(clock);
        }
    }

    private void apply(MonitorSettings settings) {
        this.httpMethod = settings.httpMethod();
        this.expectedStatusMin = (short) settings.expectedStatusMin();
        this.expectedStatusMax = (short) settings.expectedStatusMax();
        this.intervalSeconds = settings.intervalSeconds();
        this.timeoutMs = settings.timeoutMs();
        this.degradedThresholdMs = settings.degradedThresholdMs();
        this.followRedirects = settings.followRedirects();
        this.failureThreshold = (short) settings.failureThreshold();
        this.recoveryThreshold = (short) settings.recoveryThreshold();
    }

    /** Surrounding spaces are a typing slip. */
    private static String validName(String name) {
        String cleanName = name.strip();
        int length = cleanName.codePointCount(0, cleanName.length());
        if (length < 1 || length > NAME_MAX_LENGTH || !VisibleText.isValid(cleanName)) {
            throw new IllegalArgumentException("Invalid monitor name");
        }
        return cleanName;
    }

    private static URI validUrl(URI url) {
        if (url.toString().length() > URL_MAX_LENGTH) {
            throw new IllegalArgumentException("The URL is longer than " + URL_MAX_LENGTH + " characters");
        }
        return url;
    }

    /** PostgreSQL keeps microseconds: truncating here makes the returned value match what a later read returns. */
    private static Instant now(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID id() {
        return id;
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID projectId() {
        return projectId;
    }

    public String name() {
        return name;
    }

    public URI url() {
        return URI.create(url);
    }

    public MonitorSettings settings() {
        return new MonitorSettings(
                httpMethod,
                expectedStatusMin,
                expectedStatusMax,
                intervalSeconds,
                timeoutMs,
                degradedThresholdMs,
                followRedirects,
                failureThreshold,
                recoveryThreshold);
    }

    public @Nullable UUID createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * The version of the saved row, for {@code ETag} and {@code If-Match}. Not called {@code version()}: Spring Data
     * would read it to tell whether the entity is new, and it throws before the first save.
     */
    public long savedVersion() {
        return Objects.requireNonNull(version, "The monitor has not been saved yet");
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof Monitor monitor && id.equals(monitor.id()));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Never the URL: its query string may carry a token. */
    @Override
    public String toString() {
        return "Monitor[id=" + id + "]";
    }
}
