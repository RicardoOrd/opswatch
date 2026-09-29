-- Organizations (tenants) and the role of each user in them (organization module).
-- Design: docs/database/database-design.md#9-ddl-preliminar
CREATE TABLE organizations (
    id         uuid        PRIMARY KEY,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ck_organizations_name CHECK (char_length(name) BETWEEN 1 AND 100)
);

CREATE TABLE memberships (
    organization_id uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    role            text        NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_memberships PRIMARY KEY (organization_id, user_id),
    CONSTRAINT fk_memberships_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_memberships_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_memberships_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER'))
);
-- The organizations of a user: listing and the quota
CREATE INDEX ix_memberships_user ON memberships (user_id);
