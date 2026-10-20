-- migrate:up
CREATE TABLE locations (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id       TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    client_msg_id   TEXT NOT NULL,
    device_ts       INTEGER NOT NULL,
    server_ts       INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    latitude        REAL NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude       REAL NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    accuracy        REAL CHECK (accuracy BETWEEN 0 AND 10000),
    provider        TEXT NOT NULL DEFAULT 'unknown'
                    CHECK (provider IN ('gps', 'network', 'fused', 'passive', 'unknown')),
    source_crs      TEXT NOT NULL DEFAULT 'WGS84'
                    CHECK (source_crs IN ('WGS84', 'GCJ02', 'BD09')),
    speed           REAL CHECK (speed BETWEEN 0 AND 150),
    bearing         REAL CHECK (bearing >= 0 AND bearing < 360),
    battery         REAL CHECK (battery BETWEEN 0 AND 1),
    is_mock         INTEGER NOT NULL DEFAULT 0 CHECK (is_mock IN (0, 1)),
    created_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
    UNIQUE (device_id, client_msg_id)
) STRICT;

CREATE TABLE device_latest (
    device_id       TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    client_msg_id   TEXT NOT NULL,
    latitude        REAL NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude       REAL NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    accuracy        REAL CHECK (accuracy BETWEEN 0 AND 10000),
    provider        TEXT NOT NULL DEFAULT 'unknown'
                    CHECK (provider IN ('gps', 'network', 'fused', 'passive', 'unknown')),
    source_crs      TEXT NOT NULL DEFAULT 'WGS84'
                    CHECK (source_crs IN ('WGS84', 'GCJ02', 'BD09')),
    speed           REAL CHECK (speed BETWEEN 0 AND 150),
    bearing         REAL CHECK (bearing >= 0 AND bearing < 360),
    battery         REAL CHECK (battery BETWEEN 0 AND 1),
    is_mock         INTEGER NOT NULL DEFAULT 0 CHECK (is_mock IN (0, 1)),
    device_ts       INTEGER NOT NULL,
    server_ts       INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))
) STRICT;

-- migrate:down
DROP TABLE device_latest;
DROP TABLE locations;
