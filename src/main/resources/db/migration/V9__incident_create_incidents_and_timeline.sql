-- Incidents and their timeline (incident module, OW-032).
-- Design: docs/database/database-design.md#9-ddl-preliminar and docs/architecture/incident-lifecycle.md. An incident is
-- opened and resolved inside the transaction that moves the state of its monitor; it is never deleted while its
-- organization exists (docs/database/data-retention.md).
CREATE TABLE incidents (
    id                uuid        PRIMARY KEY,
    organization_id   uuid        NOT NULL,
    project_id        uuid        NOT NULL,
    monitor_id        uuid        NOT NULL,
    -- As the monitor was called when it went down: the incident is history, and a rename does not rewrite it
    monitor_name      text        NOT NULL,
    status            text        NOT NULL,
    -- The failure reason of the check that took the monitor down
    cause             text        NOT NULL,
    cause_http_status smallint,
    opened_at         timestamptz NOT NULL,
    acknowledged_at   timestamptz,
    -- Null once that user's account is deleted
    acknowledged_by   uuid,
    resolved_at       timestamptz,
    -- Who paused or deleted the monitor; null for a recovery and once that user's account is deleted
    resolved_by       uuid,
    resolution        text,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    -- An incident's organization is always its project's: authorizing by organization_id needs no join
    CONSTRAINT fk_incidents_project FOREIGN KEY (project_id, organization_id) REFERENCES projects (id, organization_id),
    -- Monitors are only ever deleted logically, so the row an incident refers to stays (OW-029)
    CONSTRAINT fk_incidents_monitor FOREIGN KEY (monitor_id) REFERENCES monitors (id),
    CONSTRAINT fk_incidents_ack_by FOREIGN KEY (acknowledged_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_incidents_resolved_by FOREIGN KEY (resolved_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_incidents_monitor_name CHECK (char_length(monitor_name) BETWEEN 1 AND 100),
    CONSTRAINT ck_incidents_status CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT ck_incidents_cause CHECK (cause IN ('TIMEOUT', 'DNS_FAILURE', 'CONNECTION_FAILED', 'TLS_FAILURE',
        'UNEXPECTED_STATUS', 'TOO_MANY_REDIRECTS', 'TARGET_BLOCKED', 'PROTOCOL_ERROR')),
    CONSTRAINT ck_incidents_resolution CHECK (resolution IN ('AUTO_RECOVERED', 'MONITOR_PAUSED', 'MONITOR_DELETED')),
    CONSTRAINT ck_incidents_resolved CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL AND resolution IS NOT NULL)),
    CONSTRAINT ck_incidents_acknowledged CHECK (status <> 'ACKNOWLEDGED' OR acknowledged_at IS NOT NULL)
);
-- At most one active incident per monitor (rule R2 of the lifecycle), whatever the application does. Opening is
-- INSERT … ON CONFLICT DO NOTHING against this index
CREATE UNIQUE INDEX ux_incidents_one_active_per_monitor ON incidents (monitor_id) WHERE status <> 'RESOLVED';
-- The incidents of an organization, newest first: its listing (OW-033)
CREATE INDEX ix_incidents_org_opened ON incidents (organization_id, opened_at DESC);
-- The incidents of a monitor, newest first
CREATE INDEX ix_incidents_monitor_opened ON incidents (monitor_id, opened_at DESC);

-- Append-only: what happened to an incident and who did it
CREATE TABLE incident_timeline (
    id            uuid        PRIMARY KEY,
    incident_id   uuid        NOT NULL,
    type          text        NOT NULL,
    -- Null for what the system did, and once that user's account is deleted
    actor_user_id uuid,
    occurred_at   timestamptz NOT NULL,
    note          text,
    CONSTRAINT fk_incident_timeline_incident FOREIGN KEY (incident_id) REFERENCES incidents (id),
    CONSTRAINT fk_incident_timeline_actor FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_incident_timeline_type CHECK (type IN ('OPENED', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT ck_incident_timeline_note CHECK (note IS NULL OR char_length(note) <= 500)
);
CREATE INDEX ix_incident_timeline_incident ON incident_timeline (incident_id, occurred_at);
