-- migrate:up
-- 只读视图：活跃设备。结构变化时新增迁移重建，不直接改本文件。
CREATE VIEW active_devices AS
SELECT
    d.id        AS device_id,
    d.user_id   AS user_id,
    d.is_primary AS is_primary
FROM devices d
WHERE d.status = 'active';

-- migrate:down
DROP VIEW active_devices;
