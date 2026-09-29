-- Rotating refresh tokens grouped in families (identity module). Design: docs/database/database-design.md#9-ddl-preliminar
-- and docs/security/security-architecture.md. Only the SHA-256 of each token is stored, never the token itself.
CREATE TABLE refresh_tokens (
    id                 uuid        PRIMARY KEY,
    user_id            uuid        NOT NULL,
    family_id          uuid        NOT NULL,
    token_hash         bytea       NOT NULL,
    issued_at          timestamptz NOT NULL,
    expires_at         timestamptz NOT NULL,
    family_expires_at  timestamptz NOT NULL,
    revoked_at         timestamptz,
    revocation_reason  text,
    replaced_by_id     uuid,
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_refresh_tokens_reason CHECK (revocation_reason IN
        ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'PASSWORD_CHANGED', 'USER_DISABLED')),
    CONSTRAINT ck_refresh_tokens_revocation CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL))
);

CREATE UNIQUE INDEX ux_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user   ON refresh_tokens (user_id);
