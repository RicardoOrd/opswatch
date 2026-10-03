-- Projects: groups of monitors inside an organization (organization module, OW-019).
-- Design: docs/database/database-design.md#9-ddl-preliminar
CREATE TABLE projects (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL,
    name            text        NOT NULL,
    description     text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    deleted_at      timestamptz,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT fk_projects_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    -- Target of the composite foreign key of monitors: a monitor's organization is always its project's
    CONSTRAINT ux_projects_id_organization UNIQUE (id, organization_id),
    CONSTRAINT ck_projects_name CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT ck_projects_description CHECK (description IS NULL OR char_length(description) <= 500)
);
-- Unique per organization whatever the case, among the projects not deleted: a deleted name can be used again
CREATE UNIQUE INDEX ux_projects_org_name ON projects (organization_id, lower(name)) WHERE deleted_at IS NULL;
-- The projects of an organization: listing, quota and deleting them with it
CREATE INDEX ix_projects_organization ON projects (organization_id) WHERE deleted_at IS NULL;
