-- Users and their credentials (identity module). Design: docs/database/database-design.md#9-ddl-preliminar
CREATE TABLE users (
    id                uuid        PRIMARY KEY,
    email             text        NOT NULL,
    display_name      text        NOT NULL,
    password_hash     text        NOT NULL,
    status            text        NOT NULL DEFAULT 'ACTIVE',
    email_verified_at timestamptz,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ck_users_email_normalized CHECK (email = lower(email) AND char_length(email) <= 254),
    CONSTRAINT ck_users_display_name     CHECK (char_length(display_name) BETWEEN 1 AND 100),
    CONSTRAINT ck_users_status           CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- Also settles two simultaneous registrations with the same email: the second INSERT fails
CREATE UNIQUE INDEX ux_users_email ON users (email);
