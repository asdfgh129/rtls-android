import type { Db } from '../connection.js';
import { newId, nowMs } from '../ids.js';
import type { DevicePlatform, DeviceStatus } from './types.js';

export interface DeviceRow {
  id: string;
  userId: string;
  name: string;
  platform: DevicePlatform;
  appVersion: string | null;
  isPrimary: boolean;
  status: DeviceStatus;
  tokenVersion: number;
  lastSeenAt: number | null;
  createdAt: number;
  updatedAt: number;
}

interface RawDeviceRow {
  id: string;
  user_id: string;
  name: string;
  platform: DevicePlatform;
  app_version: string | null;
  is_primary: number;
  status: DeviceStatus;
  token_version: number;
  last_seen_at: number | null;
  created_at: number;
  updated_at: number;
}

export interface NewDevice {
  userId: string;
  name: string;
  platform?: DevicePlatform;
  appVersion?: string | null;
  isPrimary?: boolean;
}

function mapDevice(row: RawDeviceRow): DeviceRow {
  return {
    id: row.id,
    userId: row.user_id,
    name: row.name,
    platform: row.platform,
    appVersion: row.app_version,
    isPrimary: row.is_primary === 1,
    status: row.status,
    tokenVersion: row.token_version,
    lastSeenAt: row.last_seen_at,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

const SELECT_COLUMNS =
  'id, user_id, name, platform, app_version, is_primary, status, token_version, last_seen_at, created_at, updated_at';

export class DeviceRepository {
  constructor(private readonly db: Db) {}

  create(input: NewDevice): DeviceRow {
    const id = newId();
    const ts = nowMs();
    this.db
      .prepare(
        `INSERT INTO devices (id, user_id, name, platform, app_version, is_primary, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(
        id,
        input.userId,
        input.name,
        input.platform ?? 'android',
        input.appVersion ?? null,
        input.isPrimary ? 1 : 0,
        ts,
        ts,
      );
    const created = this.findById(id);
    if (!created) {
      throw new Error(`Failed to load device ${id} after insert`);
    }
    return created;
  }

  findById(id: string): DeviceRow | null {
    const row = this.db
      .prepare(`SELECT ${SELECT_COLUMNS} FROM devices WHERE id = ?`)
      .get(id) as RawDeviceRow | undefined;
    return row ? mapDevice(row) : null;
  }

  listByUser(userId: string): DeviceRow[] {
    return (
      this.db
        .prepare(`SELECT ${SELECT_COLUMNS} FROM devices WHERE user_id = ? ORDER BY created_at ASC`)
        .all(userId) as RawDeviceRow[]
    ).map(mapDevice);
  }

  touchLastSeen(id: string, seenAt: number = nowMs()): void {
    this.db
      .prepare('UPDATE devices SET last_seen_at = ?, updated_at = ? WHERE id = ?')
      .run(seenAt, seenAt, id);
  }

  /** 撤销设备：状态置 revoked 并递增 token_version，使旧 Access Token 失效。 */
  revoke(id: string): void {
    this.db
      .prepare(
        `UPDATE devices
         SET status = 'revoked', token_version = token_version + 1, updated_at = ?
         WHERE id = ?`,
      )
      .run(nowMs(), id);
  }
}
