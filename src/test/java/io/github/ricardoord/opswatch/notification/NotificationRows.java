package io.github.ricardoord.opswatch.notification;

import static io.github.ricardoord.opswatch.identity.domain.UserBuilder.uniqueEmail;

import io.github.ricardoord.opswatch.notification.domain.ChannelType;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rows of the tests of notification written straight with SQL: users, organizations, projects, incidents and their
 * monitors, as the other modules would leave them. Each call makes fresh ones: the tests share the database.
 */
public final class NotificationRows {

    private final JdbcTemplate jdbc;

    public NotificationRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, display_name, password_hash, created_at, updated_at)
                VALUES (?, ?, 'Ana', '{bcrypt}not-a-real-hash', now(), now())""", id, uniqueEmail());
        return id;
    }

    public UUID organization() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO organizations (id, name, created_at, updated_at) VALUES (?, 'CharityLink', now(), now())",
                id);
        return id;
    }

    public UUID organizationOwnedBy(UUID owner) {
        UUID id = organization();
        jdbc.update("""
                INSERT INTO memberships (organization_id, user_id, role, created_at, updated_at)
                VALUES (?, ?, 'OWNER', now(), now())""", id, owner);
        return id;
    }

    public UUID project(UUID organization) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (id, organization_id, name, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())""", id, organization, "Project " + id);
        return id;
    }

    /** With its monitor, which has no state: nothing schedules it, and no engine of another test checks it. */
    public UUID openIncident(UUID organization, UUID project, String monitorName, Instant openedAt) {
        UUID monitor = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO monitors (id, organization_id, project_id, name, url, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'https://api.example.com/health', now(), now())""", monitor, organization, project, monitorName);
        UUID incident = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO incidents (id, organization_id, project_id, monitor_id, monitor_name, status, cause,
                                       cause_http_status, opened_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'OPEN', 'UNEXPECTED_STATUS', 503, ?, now(), now())""", incident, organization, project, monitor, monitorName, at(openedAt));
        return incident;
    }

    /**
     * A channel whose configuration is not a real ciphertext: for the tests that never send. The others create theirs
     * with {@code ChannelService}.
     */
    public UUID channel(UUID organization, @Nullable UUID project, ChannelType type, boolean enabled) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification_channels (id, organization_id, project_id, name, type, config_ciphertext,
                                                   enabled, created_at, updated_at)
                VALUES (?, ?, ?, 'On call', ?, '\\x00'::bytea, ?, now(), now())""", id, organization, project, type.name(), enabled);
        return id;
    }

    public List<Map<String, Object>> deliveriesOfIncident(UUID incident) {
        return jdbc.queryForList(
                "SELECT * FROM notification_deliveries WHERE incident_id = ? ORDER BY event_type, channel_id",
                incident);
    }

    public Map<String, Object> delivery(UUID id) {
        return jdbc.queryForMap("SELECT * FROM notification_deliveries WHERE id = ?", id);
    }

    /** As {@code queryForMap} reads a timestamptz back. */
    public static Timestamp stored(Instant instant) {
        return Timestamp.from(instant);
    }

    /** OffsetDateTime: the JDBC 4.2 type of timestamptz, which the PostgreSQL driver maps both ways. */
    public static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
