-- Notification channels and their deliveries (notification module, OW-035; the deliveries are written from OW-036).
-- Design: docs/database/database-design.md#9-ddl-preliminar and docs/architecture/domain-model.md#8-módulo-notification.
CREATE TABLE notification_channels (
    id                uuid        PRIMARY KEY,
    organization_id   uuid        NOT NULL,
    -- NULL: the incidents of every project of the organization
    project_id        uuid,
    name              text        NOT NULL,
    type              text        NOT NULL,
    -- JSON encrypted by SecretCipher with notification_channels.config:<id> as associated data. EMAIL:
    -- {"recipients": [...]}; WEBHOOK: {"url": "...", "signingSecret": "..."}
    config_ciphertext bytea       NOT NULL,
    enabled           boolean     NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT fk_notification_channels_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    -- A channel's project is always of its organization. MATCH SIMPLE: not checked when project_id is NULL
    CONSTRAINT fk_notification_channels_project FOREIGN KEY (project_id, organization_id)
        REFERENCES projects (id, organization_id),
    CONSTRAINT ck_notification_channels_type CHECK (type IN ('EMAIL', 'WEBHOOK')),
    CONSTRAINT ck_notification_channels_name CHECK (char_length(name) BETWEEN 1 AND 100)
);
-- The channels of an organization: listing and quota
CREATE INDEX ix_notification_channels_org ON notification_channels (organization_id);
-- The channels of a project: deleted with it
CREATE INDEX ix_notification_channels_project ON notification_channels (project_id) WHERE project_id IS NOT NULL;

CREATE TABLE notification_deliveries (
    id              uuid        PRIMARY KEY,
    channel_id      uuid        NOT NULL,
    -- NULL only for a TEST delivery, the test of a channel (OW-036)
    incident_id     uuid,
    event_type      text        NOT NULL,
    status          text        NOT NULL DEFAULT 'PENDING',
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_attempt_at timestamptz,
    last_error      text,
    created_at      timestamptz NOT NULL,
    sent_at         timestamptz,
    CONSTRAINT fk_notification_deliveries_channel FOREIGN KEY (channel_id)
        REFERENCES notification_channels (id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_deliveries_incident FOREIGN KEY (incident_id) REFERENCES incidents (id),
    -- A repeated incident event creates no second delivery. TEST deliveries have no incident and never collide: NULLs
    -- are distinct
    CONSTRAINT ux_notification_deliveries_once UNIQUE (channel_id, incident_id, event_type),
    CONSTRAINT ck_notification_deliveries_event CHECK (event_type IN ('INCIDENT_OPENED', 'INCIDENT_RESOLVED', 'TEST')),
    CONSTRAINT ck_notification_deliveries_incident CHECK ((event_type = 'TEST') = (incident_id IS NULL)),
    CONSTRAINT ck_notification_deliveries_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_notification_deliveries_error CHECK (last_error IS NULL OR char_length(last_error) <= 255)
);
-- What the worker claims next
CREATE INDEX ix_notification_deliveries_due ON notification_deliveries (next_attempt_at) WHERE status = 'PENDING';
