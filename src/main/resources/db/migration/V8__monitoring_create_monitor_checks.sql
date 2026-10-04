-- The result of every check, append-only (monitoring module, OW-027).
-- Design: docs/database/database-design.md#9-ddl-preliminar. Inserted with JDBC by CheckResultRecorder, never updated;
-- the retention job (OW-029) deletes by checked_at, which the BRIN index serves at a fraction of the size of a B-tree.
CREATE TABLE monitor_checks (
    monitor_id       uuid        NOT NULL,
    -- When the check started
    checked_at       timestamptz NOT NULL,
    status           text        NOT NULL,
    http_status      smallint,
    response_time_ms integer,
    failure_reason   text,
    -- A generic text of OpsWatch, never anything the target sent
    error_detail     text,
    CONSTRAINT pk_monitor_checks PRIMARY KEY (monitor_id, checked_at),
    CONSTRAINT fk_monitor_checks_monitor FOREIGN KEY (monitor_id) REFERENCES monitors (id),
    CONSTRAINT ck_monitor_checks_status CHECK (status IN ('UP', 'DEGRADED', 'DOWN')),
    CONSTRAINT ck_monitor_checks_reason CHECK (failure_reason IN ('TIMEOUT', 'DNS_FAILURE', 'CONNECTION_FAILED',
        'TLS_FAILURE', 'UNEXPECTED_STATUS', 'TOO_MANY_REDIRECTS', 'TARGET_BLOCKED', 'PROTOCOL_ERROR')),
    CONSTRAINT ck_monitor_checks_reason_consistency CHECK ((status = 'DOWN') = (failure_reason IS NOT NULL)),
    CONSTRAINT ck_monitor_checks_detail CHECK (error_detail IS NULL OR char_length(error_detail) <= 255)
);
CREATE INDEX ix_monitor_checks_checked_at_brin ON monitor_checks USING brin (checked_at);
