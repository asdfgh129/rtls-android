import type { Db } from '../connection.js';
import { writeTransaction } from '../connection.js';
import type {
  CoordinateCrs,
  HistoryCursor,
  HistoryPage,
  HistoryPoint,
  HistoryQuery,
  InsertResult,
  LatestLocation,
  LocationProvider,
  LocationRepository,
  NewLocationPoint,
} from './types.js';

interface RawLatestRow {
  device_id: string;
  client_msg_id: string;
  latitude: number;
  longitude: number;
  accuracy: number | null;
  provider: LocationProvider;
  source_crs: CoordinateCrs;
  speed: number | null;
  bearing: number | null;
  battery: number | null;
  is_mock: number;
  device_ts: number;
  server_ts: number;
  updated_at: number;
}

interface RawHistoryRow {
  id: number;
  device_ts: number;
  server_ts: number;
  latitude: number;
  longitude: number;
  accuracy: number | null;
  provider: LocationProvider;
  source_crs: CoordinateCrs;
}

const HISTORY_COLUMNS =
  'id, device_ts, server_ts, latitude, longitude, accuracy, provider, source_crs';

const INSERT_LOCATION_SQL = `
  INSERT INTO locations (
    device_id, client_msg_id, device_ts, server_ts,
    latitude, longitude, accuracy, provider, source_crs,
    speed, bearing, battery, is_mock
  )
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  ON CONFLICT (device_id, client_msg_id) DO NOTHING
  RETURNING id`;

const UPSERT_LATEST_SQL = `
  INSERT INTO device_latest (
    device_id, client_msg_id, latitude, longitude, accuracy, provider, source_crs,
    speed, bearing, battery, is_mock, device_ts, server_ts, updated_at
  )
  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  ON CONFLICT (device_id) DO UPDATE SET
    client_msg_id = excluded.client_msg_id,
    latitude      = excluded.latitude,
    longitude     = excluded.longitude,
    accuracy      = excluded.accuracy,
    provider      = excluded.provider,
    source_crs    = excluded.source_crs,
    speed         = excluded.speed,
    bearing       = excluded.bearing,
    battery       = excluded.battery,
    is_mock       = excluded.is_mock,
    device_ts     = excluded.device_ts,
    server_ts     = excluded.server_ts,
    updated_at    = excluded.updated_at
  WHERE excluded.server_ts > device_latest.server_ts
     OR (excluded.server_ts = device_latest.server_ts
         AND excluded.device_ts >= device_latest.device_ts)`;

const DELETE_BATCH_SQL = `
  DELETE FROM locations
  WHERE id IN (
    SELECT id FROM locations WHERE server_ts < ? ORDER BY server_ts ASC, id ASC LIMIT ?
  )`;

export const MAX_HISTORY_LIMIT = 5000;

export function encodeCursor(cursor: HistoryCursor): string {
  return Buffer.from(`${cursor.serverTs}:${cursor.id}`, 'utf8').toString('base64url');
}

export function decodeCursor(raw: string): HistoryCursor | null {
  const decoded = Buffer.from(raw, 'base64url').toString('utf8');
  const separator = decoded.indexOf(':');
  if (separator <= 0) {
    return null;
  }
  const serverTs = Number(decoded.slice(0, separator));
  const id = Number(decoded.slice(separator + 1));
  if (!Number.isSafeInteger(serverTs) || !Number.isSafeInteger(id)) {
    return null;
  }
  return { serverTs, id };
}

export class SqliteLocationRepository implements LocationRepository {
  constructor(private readonly db: Db) {}

  /**
   * 批量写点：单写事务内逐条 INSERT ... ON CONFLICT DO NOTHING，
   * 再以 server_ts 单调守卫 UPSERT device_latest（迟到旧点不回退）。
   */
  async insertBatch(points: NewLocationPoint[]): Promise<InsertResult> {
    if (points.length === 0) {
      return { inserted: 0, duplicates: 0, ids: [] };
    }

    const insertLocation = this.db.prepare(INSERT_LOCATION_SQL);
    const upsertLatest = this.db.prepare(UPSERT_LATEST_SQL);

    return writeTransaction(this.db, () => {
      const ids: number[] = [];
      let duplicates = 0;

      for (const point of points) {
        const inserted = insertLocation.get(
          point.deviceId,
          point.clientMsgId,
          point.deviceTs,
          point.serverTs,
          point.latitude,
          point.longitude,
          point.accuracy ?? null,
          point.provider ?? 'unknown',
          point.sourceCrs ?? 'WGS84',
          point.speed ?? null,
          point.bearing ?? null,
          point.battery ?? null,
          point.isMock ? 1 : 0,
        ) as { id: number } | undefined;

        if (!inserted) {
          duplicates += 1;
          continue;
        }

        ids.push(inserted.id);
        upsertLatest.run(
          point.deviceId,
          point.clientMsgId,
          point.latitude,
          point.longitude,
          point.accuracy ?? null,
          point.provider ?? 'unknown',
          point.sourceCrs ?? 'WGS84',
          point.speed ?? null,
          point.bearing ?? null,
          point.battery ?? null,
          point.isMock ? 1 : 0,
          point.deviceTs,
          point.serverTs,
          point.serverTs,
        );
      }

      return { inserted: ids.length, duplicates, ids };
    });
  }

  async latest(deviceId: string): Promise<LatestLocation | null> {
    const row = this.db
      .prepare(
        `SELECT device_id, client_msg_id, latitude, longitude, accuracy, provider, source_crs,
                speed, bearing, battery, is_mock, device_ts, server_ts, updated_at
         FROM device_latest WHERE device_id = ?`,
      )
      .get(deviceId) as RawLatestRow | undefined;

    if (!row) {
      return null;
    }

    return {
      deviceId: row.device_id,
      clientMsgId: row.client_msg_id,
      latitude: row.latitude,
      longitude: row.longitude,
      accuracy: row.accuracy,
      provider: row.provider,
      sourceCrs: row.source_crs,
      speed: row.speed,
      bearing: row.bearing,
      battery: row.battery,
      isMock: row.is_mock === 1,
      deviceTs: row.device_ts,
      serverTs: row.server_ts,
      updatedAt: row.updated_at,
    };
  }

  /**
   * 历史查询：半开区间 [from, to)，行值游标 keyset 分页
   * （(server_ts, id) 严格递增），不偏移、不 COUNT。
   */
  async queryHistory(input: HistoryQuery): Promise<HistoryPage> {
    // SQLite 的 LIMIT -1 表示不限制；这里强制收敛到协议允许的 [1, 5000]。
    const limit = Math.min(Math.max(Math.trunc(input.limit), 1), MAX_HISTORY_LIMIT);
    const conditions = ['device_id = ?', 'server_ts >= ?', 'server_ts < ?'];
    const params: (string | number)[] = [input.deviceId, input.from, input.to];

    if (input.cursor) {
      conditions.push('(server_ts > ? OR (server_ts = ? AND id > ?))');
      params.push(input.cursor.serverTs, input.cursor.serverTs, input.cursor.id);
    }

    const fetchLimit = limit + 1;
    params.push(fetchLimit);

    const rows = this.db
      .prepare(
        `SELECT ${HISTORY_COLUMNS}
         FROM locations
         WHERE ${conditions.join(' AND ')}
         ORDER BY server_ts ASC, id ASC
         LIMIT ?`,
      )
      .all(...params) as RawHistoryRow[];

    const truncated = rows.length > limit;
    const pageRows = truncated ? rows.slice(0, limit) : rows;

    const points: HistoryPoint[] = pageRows.map((row) => ({
      id: row.id,
      deviceTs: row.device_ts,
      serverTs: row.server_ts,
      latitude: row.latitude,
      longitude: row.longitude,
      accuracy: row.accuracy,
      provider: row.provider,
      sourceCrs: row.source_crs,
    }));

    const last = points.at(-1);
    return {
      points,
      nextCursor: truncated && last ? { serverTs: last.serverTs, id: last.id } : null,
      truncated,
    };
  }

  /**
   * 分批删除过期点（SQLite 无 DELETE ... USING）。
   * 每批一个独立写事务，避免长事务持有写锁；删完做一次增量 vacuum。
   */
  async deleteOlderThan(cutoff: Date): Promise<number> {
    const cutoffMs = cutoff.getTime();
    const batchSize = 5000;
    let deleted = 0;

    for (let batch = 0; batch < 10_000; batch += 1) {
      const changes = writeTransaction(this.db, () =>
        this.db.prepare(DELETE_BATCH_SQL).run(cutoffMs, batchSize).changes,
      );
      deleted += changes;
      if (changes < batchSize) {
        break;
      }
    }

    if (deleted > 0) {
      this.db.pragma('incremental_vacuum(1000)');
    }

    return deleted;
  }
}

/** 判定最新点是否已超出新鲜度阈值（协议 §4.3）。 */
export function isStale(latest: LatestLocation | null, now: number, thresholdMs: number): boolean {
  if (!latest) {
    return true;
  }
  return now - latest.serverTs > thresholdMs;
}
