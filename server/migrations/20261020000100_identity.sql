-- migrate:up
CREATE TABLE users (
    id              TEXT PRIMARY KEY,
    display_name    TEXT NOT NULL CHECK (length(display_name) BETWEEN 1 AND 64),
    friend_code     TEXT NOT NULL UNIQUE
                    CHECK (friend_code GLOB '[A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9][A-Z0-9]'),
    share_enabled   INTEGER NOT NULL DEFAULT 1 CHECK (share_enabled IN (0, 1)),
    created_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    updated_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
) STRICT;

CREATE TABLE devices (
    id              TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 64),
    platform        TEXT NOT NULL DEFAULT 'android'
                    CHECK (platform IN ('android', 'ios', 'unknown')),
    app_version     TEXT,
    is_primary      INTEGER NOT NULL DEFAULT 0 CHECK (is_primary IN (0, 1)),
    status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active', 'revoked')),
    token_version   INTEGER NOT NULL DEFAULT 1 CHECK (token_version > 0),
    last_seen_at    INTEGER,
    created_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    updated_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
) STRICT;

CREATE TABLE refresh_tokens (
    id              TEXT PRIMARY KEY,
    device_id       TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    token_hash      BLOB NOT NULL UNIQUE,
    family_id       TEXT NOT NULL,
    expires_at      INTEGER NOT NULL,
    revoked_at      INTEGER,
    replaced_by     TEXT REFERENCES refresh_tokens(id) ON DELETE SET NULL,
    created_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
) STRICT;

-- migrate:down
DROP TABLE refresh_tokens;
DROP TABLE devices;
DROP TABLE users;
