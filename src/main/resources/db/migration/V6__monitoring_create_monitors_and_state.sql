-- Monitors and their execution state (monitoring module, OW-021).
-- Design: docs/database/database-design.md#9-ddl-preliminar. request_headers arrives with OW-022 and monitor_checks
-- with OW-027. There is no enabled column: a monitor is paused when its state is PAUSED.
CREATE TABLE monitors (
    id                    uuid        PRIMARY KEY,
    organization_id       uuid        NOT NULL,
    project_id            uuid        NOT NULL,
    name                  text        NOT NULL,
    url                   text        NOT NULL,
    http_method           text        NOT NULL DEFAULT 'GET',
    expected_status_min   smallint    NOT NULL DEFAULT 200,
    expected_status_max   smallint    NOT NULL DEFAULT 299,
    interval_seconds      integer     NOT NULL DEFAULT 60,
    timeout_ms            integer     NOT NULL DEFAULT 10000,
    degraded_threshold_ms integer,
    follow_redirects      boolean     NOT NULL DEFAULT true,
    failure_threshold     smallint    NOT NULL DEFAULT 3,
    recovery_threshold    smallint    NOT NULL DEFAULT 2,
    created_by            uuid,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    deleted_at            timestamptz,
    version               bigint      NOT NULL DEFAULT 0,
    -- A monitor's organization is always its project's: authorizing by organization_id needs no join
    CONSTRAINT fk_monitors_project FOREIGN KEY (project_id, organization_id)
        REFERENCES projects (id, organization_id),
    CONSTRAINT fk_monitors_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_monitors_name      CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT ck_monitors_url       CHECK (char_length(url) <= 2048),
    CONSTRAINT ck_monitors_method    CHECK (http_method IN ('GET', 'HEAD')),
    CONSTRAINT ck_monitors_status    CHECK (expected_status_min BETWEEN 100 AND 599
                                        AND expected_status_max BETWEEN 100 AND 599
                                        AND expected_status_min <= expected_status_max),
    CONSTRAINT ck_monitors_interval  CHECK (interval_seconds BETWEEN 30 AND 3600),
    CONSTRAINT ck_monitors_timeout   CHECK (timeout_ms BETWEEN 1000 AND 30000
                                        AND timeout_ms < interval_seconds * 1000),
    CONSTRAINT ck_monitors_degraded  CHECK (degraded_threshold_ms IS NULL
                                        OR degraded_threshold_ms BETWEEN 1 AND timeout_ms),
    CONSTRAINT ck_monitors_thresholds CHECK (failure_threshold BETWEEN 1 AND 10
                                        AND recovery_threshold BETWEEN 1 AND 10)
);
-- The monitors of a project: listing, summary and deleting them with it (OW-044)
CREATE INDEX ix_monitors_project      ON monitors (project_id)      WHERE deleted_at IS NULL;
-- The monitors of an organization: its quota
CREATE INDEX ix_monitors_organization ON monitors (organization_id) WHERE deleted_at IS NULL;
-- Unique per project whatever the case, among the monitors not deleted: a deleted name can be used again
CREATE UNIQUE INDEX ux_monitors_project_name ON monitors (project_id, lower(name)) WHERE deleted_at IS NULL;

-- Written by people with optimistic locking (monitors) and by the engine on every check (monitor_state): in one row,
-- every check would bump the version and most human edits would fail (docs/architecture/domain-model.md)
CREATE TABLE monitor_state (
    monitor_id            uuid        PRIMARY KEY,
    status                text        NOT NULL DEFAULT 'PENDING',
    status_changed_at     timestamptz NOT NULL,
    consecutive_failures  integer     NOT NULL DEFAULT 0,
    consecutive_successes integer     NOT NULL DEFAULT 0,
    last_checked_at       timestamptz,
    last_check_status     text,
    last_http_status      smallint,
    last_response_time_ms integer,
    last_failure_reason   text,
    -- NULL = not scheduled (paused or deleted)
    next_check_at         timestamptz,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT fk_monitor_state_monitor FOREIGN KEY (monitor_id) REFERENCES monitors (id),
    CONSTRAINT ck_monitor_state_status CHECK (status IN ('PENDING', 'UP', 'DEGRADED', 'DOWN', 'PAUSED')),
    CONSTRAINT ck_monitor_state_paused CHECK (status <> 'PAUSED' OR next_check_at IS NULL)
);
-- The scheduler's query: only the monitors due (docs/architecture/monitoring-engine.md#algoritmo-de-programación)
CREATE INDEX ix_monitor_state_due ON monitor_state (next_check_at) WHERE next_check_at IS NOT NULL;
