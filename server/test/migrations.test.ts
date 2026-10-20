import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { Db } from '../src/db/connection.js';
import { integrityCheck } from '../src/db/connection.js';
import { newId, nowMs } from '../src/db/ids.js';
import {
  applyMigrations,
  createTempDatabase,
  migrationFiles,
  objectExists,
  rollbackMigrations,
  tableExists,
  type TempDatabase,
} from './helpers.js';

const EXPECTED_TABLES = [
  'users',
  'devices',
  'refresh_tokens',
  'invite_codes',
  'friend_requests',
  'friendships',
  'blocks',
  'locations',
  'device_latest',
];

function seedUser(db: Db, suffix: string): string {
  const id = newId();
  const ts = nowMs();
  db.prepare(
    'INSERT INTO users (id, display_name, friend_code, created_at, updated_at) VALUES (?, ?, ?, ?, ?)',
  ).run(id, `user-${suffix}`, `FRIEND${suffix}`, ts, ts);
  return id;
}

function seedDevice(db: Db, userId: string): string {
  const id = newId();
  const ts = nowMs();
  db.prepare(
    `INSERT INTO devices (id, user_id, name, is_primary, created_at, updated_at)
     VALUES (?, ?, ?, 1, ?, ?)`,
  ).run(id, userId, 'Pixel 8', ts, ts);
  return id;
}

describe('dbmate migrations', () => {
  let temp: TempDatabase;

  beforeAll(() => {
    temp = createTempDatabase();
  });

  afterAll(() => {
    temp.cleanup();
  });

  it('每个文件都有 up/down 段', () => {
    expect(migrationFiles().length).toBeGreaterThanOrEqual(5);
  });

  it('up 建表后可过 integrity_check', () => {
    const applied = applyMigrations(temp.db);
    expect(applied).toHaveLength(migrationFiles().length);
    expect(integrityCheck(temp.db)).toBe('ok');
    for (const table of EXPECTED_TABLES) {
      expect(tableExists(temp.db, table)).toBe(true);
    }
    expect(objectExists(temp.db, 'view', 'active_devices')).toBe(true);
  });

  it('auto_vacuum 固化为 INCREMENTAL，增量回收才有效', () => {
    expect(temp.db.pragma('auto_vacuum', { simple: true })).toBe(2);
  });

  it('STRICT 表拒绝类型错误写入', () => {
    const userId = seedUser(temp.db, '01');
    expect(() =>
      temp.db
        .prepare('INSERT INTO devices (id, user_id, name, is_primary, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)')
        .run(newId(), userId, 'bad-primary', 'yes', nowMs(), nowMs()),
    ).toThrow();
  });

  it('friend_code 必须恰为 8 位大写字母数字', () => {
    const ts = nowMs();
    const insert = temp.db.prepare(
      'INSERT INTO users (id, display_name, friend_code, created_at, updated_at) VALUES (?, ?, ?, ?, ?)',
    );
    for (const bad of ['abcdefgh', 'ABCDEFG', 'ABCDEFGHI', 'ABCD-EFG']) {
      expect(() => insert.run(newId(), 'bad', bad, ts, ts)).toThrow();
    }
  });

  it('friendships 强制 user_low_id < user_high_id', () => {
    const a = seedUser(temp.db, '02');
    const b = seedUser(temp.db, '03');
    const [low, high] = a < b ? [a, b] : [b, a];
    expect(() =>
      temp.db
        .prepare('INSERT INTO friendships (user_low_id, user_high_id, created_at) VALUES (?, ?, ?)')
        .run(high, low, nowMs()),
    ).toThrow();
  });

  it('locations (device_id, client_msg_id) 幂等', () => {
    const userId = seedUser(temp.db, '04');
    const deviceId = seedDevice(temp.db, userId);
    const insert = temp.db.prepare(
      `INSERT INTO locations (device_id, client_msg_id, device_ts, server_ts, latitude, longitude)
       VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (device_id, client_msg_id) DO NOTHING`,
    );
    insert.run(deviceId, 'msg-1', nowMs(), nowMs(), 31.2, 121.4);
    insert.run(deviceId, 'msg-1', nowMs(), nowMs(), 31.2, 121.4);
    const count = temp.db
      .prepare('SELECT COUNT(*) AS n FROM locations WHERE device_id = ?')
      .get(deviceId) as { n: number };
    expect(count.n).toBe(1);
  });

  it('纬度越界被 CHECK 拒绝', () => {
    const userId = seedUser(temp.db, '05');
    const deviceId = seedDevice(temp.db, userId);
    expect(() =>
      temp.db
        .prepare(
          `INSERT INTO locations (device_id, client_msg_id, device_ts, server_ts, latitude, longitude)
           VALUES (?, ?, ?, ?, ?, ?)`,
        )
        .run(deviceId, 'msg-bad', nowMs(), nowMs(), 91, 121.4),
    ).toThrow();
  });

  it('down 可完整回滚，再 up 仍可重建', () => {
    const rolledBack = rollbackMigrations(temp.db);
    expect(rolledBack).toHaveLength(migrationFiles().length);
    for (const table of EXPECTED_TABLES) {
      expect(tableExists(temp.db, table)).toBe(false);
    }
    expect(objectExists(temp.db, 'view', 'active_devices')).toBe(false);

    applyMigrations(temp.db);
    expect(integrityCheck(temp.db)).toBe('ok');
    for (const table of EXPECTED_TABLES) {
      expect(tableExists(temp.db, table)).toBe(true);
    }
  });
});
