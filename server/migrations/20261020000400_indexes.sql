-- migrate:up
CREATE INDEX devices_user_id_idx ON devices(user_id);

CREATE UNIQUE INDEX devices_one_primary_per_user_idx
    ON devices(user_id)
    WHERE is_primary = 1;

CREATE INDEX refresh_tokens_device_id_idx ON refresh_tokens(device_id);
CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens(family_id);
CREATE INDEX refresh_tokens_expires_at_idx ON refresh_tokens(expires_at);

CREATE INDEX invite_codes_expires_at_idx ON invite_codes(expires_at);

CREATE UNIQUE INDEX friend_requests_pending_direction_idx
    ON friend_requests(requester_user_id, target_user_id)
    WHERE status = 'pending';

CREATE INDEX friend_requests_target_idx
    ON friend_requests(target_user_id, status, created_at DESC);

CREATE INDEX friendships_user_high_idx ON friendships(user_high_id);

CREATE INDEX blocks_blocked_idx ON blocks(blocked_user_id);

CREATE INDEX locations_device_server_ts_idx
    ON locations(device_id, server_ts DESC, id DESC);

CREATE INDEX locations_device_device_ts_idx
    ON locations(device_id, device_ts DESC);

CREATE INDEX locations_server_ts_idx
    ON locations(server_ts);

-- migrate:down
DROP INDEX locations_server_ts_idx;
DROP INDEX locations_device_device_ts_idx;
DROP INDEX locations_device_server_ts_idx;
DROP INDEX blocks_blocked_idx;
DROP INDEX friendships_user_high_idx;
DROP INDEX friend_requests_target_idx;
DROP INDEX friend_requests_pending_direction_idx;
DROP INDEX invite_codes_expires_at_idx;
DROP INDEX refresh_tokens_expires_at_idx;
DROP INDEX refresh_tokens_family_id_idx;
DROP INDEX refresh_tokens_device_id_idx;
DROP INDEX devices_one_primary_per_user_idx;
DROP INDEX devices_user_id_idx;
