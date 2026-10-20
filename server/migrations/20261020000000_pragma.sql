-- migrate:up transaction:false
-- dbmate 会先建 schema_migrations，此时 auto_vacuum 已无法直接生效，必须先 VACUUM 固化。
PRAGMA auto_vacuum = INCREMENTAL;
VACUUM;

-- migrate:down transaction:false
PRAGMA auto_vacuum = NONE;
VACUUM;
