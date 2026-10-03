-- Event Publication Registry of Spring Modulith (OW-034): the transactional outbox of the asynchronous listeners.
-- Design: docs/architecture/events.md#4-semántica-transaccional
--
-- The columns are those of the v2 schema that Spring Modulith 2.1.1 ships for PostgreSQL
-- (org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql*.sql): Spring Modulith reads and writes them
-- by name. Flyway owns the schema (spring.modulith.events.jdbc.schema-initialization.enabled=false), so a Spring
-- Modulith upgrade that changes it needs a new migration here. Index names follow docs/database/database-design.md.

-- Pending publications: work not done yet. Nothing in OpsWatch deletes from this table.
CREATE TABLE event_publication (
    id                     uuid        NOT NULL,
    listener_id            text        NOT NULL,
    event_type             text        NOT NULL,
    serialized_event       text        NOT NULL,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    integer,
    last_resubmission_date timestamptz,
    CONSTRAINT pk_event_publication PRIMARY KEY (id)
);
-- Completing a publication looks it up by its event and listener
CREATE INDEX ix_event_publication_serialized_event ON event_publication USING hash (serialized_event);
CREATE INDEX ix_event_publication_completion_date ON event_publication (completion_date);

-- Completed publications (spring.modulith.events.completion-mode=archive), purged after
-- opswatch.retention.event-publications by EventPublicationPurgeJob
CREATE TABLE event_publication_archive (
    id                     uuid        NOT NULL,
    listener_id            text        NOT NULL,
    event_type             text        NOT NULL,
    serialized_event       text        NOT NULL,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    integer,
    last_resubmission_date timestamptz,
    CONSTRAINT pk_event_publication_archive PRIMARY KEY (id)
);
CREATE INDEX ix_event_publication_archive_serialized_event ON event_publication_archive USING hash (serialized_event);
-- The purge: completed before a date
CREATE INDEX ix_event_publication_archive_completion_date ON event_publication_archive (completion_date);
