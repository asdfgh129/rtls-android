-- migrate:up
CREATE TABLE invite_codes (
    id                  TEXT PRIMARY KEY,
    code_hash           BLOB NOT NULL UNIQUE,
    created_by_user_id  TEXT REFERENCES users(id) ON DELETE SET NULL,
    used_by_user_id     TEXT REFERENCES users(id) ON DELETE SET NULL,
    expires_at          INTEGER NOT NULL,
    used_at             INTEGER,
    created_at          INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
) STRICT;

CREATE TABLE friend_requests (
    id                  TEXT PRIMARY KEY,
    requester_user_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status              TEXT NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending', 'accepted', 'rejected', 'cancelled')),
    created_at          INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    responded_at        INTEGER,
    CHECK (requester_user_id <> target_user_id)
) STRICT;

CREATE TABLE friendships (
    user_low_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_high_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    PRIMARY KEY (user_low_id, user_high_id),
    CHECK (user_low_id < user_high_id)
) STRICT;

CREATE TABLE blocks (
    blocker_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    PRIMARY KEY (blocker_user_id, blocked_user_id),
    CHECK (blocker_user_id <> blocked_user_id)
) STRICT;

-- migrate:down
DROP TABLE blocks;
DROP TABLE friendships;
DROP TABLE friend_requests;
DROP TABLE invite_codes;
