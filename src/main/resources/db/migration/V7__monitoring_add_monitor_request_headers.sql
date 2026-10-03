-- The headers of each monitor, encrypted (OW-022). Design: docs/database/database-design.md#9-ddl-preliminar.
-- AES-256-GCM by SecretCipher: keyId (1 byte) || nonce (12) || ciphertext || tag (16), with
-- 'monitors.request_headers:<id>' as associated data. NULL when the monitor has no headers. Adding a nullable column
-- without a default only touches the catalog: no rewrite of the table, no long lock.
ALTER TABLE monitors ADD COLUMN request_headers bytea;
