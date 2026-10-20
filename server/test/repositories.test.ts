import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { Db } from '../src/db/connection.js';
import { createRepositories, type Repositories } from '../src/db/repositories/index.js';
import type { NewLocationPoint } from '../src/db/repositories/types.js';
import { applyMigrations, createTempDatabase, type TempDatabase } from './helpers.js';

let temp: TempDatabase;
let db: Db;
let repos: Repositories;

function makePoint(deviceId: string, index: number, overrides: Partial<NewLocationPoint> = {}): NewLocationPoint {
  return {
    deviceId,
    clientMsgId: `msg-${index}`,
    deviceTs: 1_700_000_000_000 + index,
    serverTs: 1_700_000_000_000 + index,
    latitude: 31.2 + index / 10_000,
    longitude: 121.4 + index / 10_000,
    accuracy: 5,
    provider: 'gps',
    ...overrides,
  };
}

const HISTORY_BASE_TS = 2_000_000_000_000;
const HISTORY_SAMPLE_INTERVAL_MS = 1000;
const HISTORY_SAMPLE_COUNT = 10;
const HISTORY_WINDOW_END_TS = HISTORY_BASE_TS + HISTORY_SAMPLE_COUNT * HISTORY_SAMPLE_INTERVAL_MS;
const RETENTION_CUTOFF_TS = HISTORY_BASE_TS + 5000;

let userA = '';
let userB = '';
let deviceA = '';
let deviceB = '';

beforeAll(() => {
  temp = createTempDatabase();
  db = temp.db;
  applyMigrations(db);
  repos = createRepositories(db);

  userA = repos.users.create({ displayName: 'Alice', friendCode: 'USERAAAA' }).id;
  userB = repos.users.create({ displayName: 'Bob', friendCode: 'USERBBBB' }).id;
  deviceA = repos.devices.create({ userId: userA, name: 'Alice Phone', isPrimary: true }).id;
  deviceB = repos.devices.create({ userId: userB, name: 'Bob Phone', isPrimary: true }).id;
});

afterAll(() => {
  temp.cleanup();
});

describe('SqliteLocationRepository.insertBatch', () => {
  it('首次插入全部落库', async () => {
    const result = await repos.locations.insertBatch([
      makePoint(deviceA, 1),
      makePoint(deviceA, 2),
      makePoint(deviceA, 3),
    ]);
    expect(result.inserted).toBe(3);
    expect(result.duplicates).toBe(0);
    expect(result.ids).toHaveLength(3);
    expect(result.ids.every((id) => Number.isSafeInteger(id))).toBe(true);
  });

  it('同 (device_id, client_msg_id) 重放只记 duplicates', async () => {
    const result = await repos.locations.insertBatch([
      makePoint(deviceA, 2),
      makePoint(deviceA, 3),
    ]);
    expect(result.inserted).toBe(0);
    expect(result.duplicates).toBe(2);
  });

  it('空数组是 no-op', async () => {
    const result = await repos.locations.insertBatch([]);
    expect(result).toEqual({ inserted: 0, duplicates: 0, ids: [] });
  });
});

describe('device_latest 单调守卫', () => {
  it('同批相同 server_ts 时取 device_ts 最大的点', async () => {
    const deviceId = repos.devices.create({ userId: userA, name: 'Batch Phone' }).id;
    const ts = 1_950_000_000_000;
    await repos.locations.insertBatch([
      { deviceId, clientMsgId: 'p1', deviceTs: ts + 1000, serverTs: ts, latitude: 1, longitude: 1 },
      { deviceId, clientMsgId: 'p2', deviceTs: ts + 2000, serverTs: ts, latitude: 2, longitude: 2 },
      { deviceId, clientMsgId: 'p3', deviceTs: ts + 3000, serverTs: ts, latitude: 3, longitude: 3 },
    ]);

    const latest = await repos.locations.latest(deviceId);
    expect(latest?.clientMsgId).toBe('p3');
    expect(latest?.latitude).toBe(3);
  });

  it('迟到旧点不覆盖较新的 device_latest', async () => {
    const before = await repos.locations.latest(deviceA);
    expect(before).not.toBeNull();

    // server_ts 比当前最新点更早 —— 应入 locations 但 latest 保持不变
    await repos.locations.insertBatch([
      makePoint(deviceA, 99, { serverTs: 1_000, deviceTs: 1_000 }),
    ]);

    const after = await repos.locations.latest(deviceA);
    expect(after?.serverTs).toBe(before?.serverTs);
    expect(after?.clientMsgId).toBe(before?.clientMsgId);
  });

  it('更新的点推进 device_latest', async () => {
    await repos.locations.insertBatch([makePoint(deviceA, 4, { serverTs: 1_900_000_000_000 })]);
    const latest = await repos.locations.latest(deviceA);
    expect(latest?.serverTs).toBe(1_900_000_000_000);
    expect(latest?.clientMsgId).toBe('msg-4');
  });
});

describe('queryHistory 行值游标分页', () => {
  beforeAll(async () => {
    const batch: NewLocationPoint[] = [];
    for (let i = 0; i < HISTORY_SAMPLE_COUNT; i += 1) {
      batch.push(makePoint(deviceB, i, { serverTs: HISTORY_BASE_TS + i * HISTORY_SAMPLE_INTERVAL_MS }));
    }
    await repos.locations.insertBatch(batch);
  });

  it('limit=2 时返回 truncated 与 nextCursor', async () => {
    const page = await repos.locations.queryHistory({
      deviceId: deviceB,
      from: HISTORY_BASE_TS,
      to: HISTORY_WINDOW_END_TS,
      limit: 2,
    });
    expect(page.points).toHaveLength(2);
    expect(page.truncated).toBe(true);
    expect(page.nextCursor).not.toBeNull();
    expect(page.points[0]?.serverTs).toBe(HISTORY_BASE_TS);
  });

  it('沿游标翻页无重复、无遗漏', async () => {
    const seen: number[] = [];
    let cursor = null as null | { serverTs: number; id: number };
    let guard = 0;

    for (;;) {
      guard += 1;
      expect(guard).toBeLessThan(20);

      const page = await repos.locations.queryHistory({
        deviceId: deviceB,
        from: HISTORY_BASE_TS,
        to: HISTORY_WINDOW_END_TS,
        limit: 3,
        cursor,
      });
      seen.push(...page.points.map((point) => point.serverTs));
      if (!page.nextCursor) {
        break;
      }
      cursor = page.nextCursor;
    }

    expect(seen).toHaveLength(10);
    expect(new Set(seen).size).toBe(10);
    expect([...seen]).toEqual([...seen].sort((a, b) => a - b));
  });

  it('半开区间 to 端不含边界', async () => {
    const page = await repos.locations.queryHistory({
      deviceId: deviceB,
      from: HISTORY_BASE_TS,
      to: HISTORY_BASE_TS + HISTORY_SAMPLE_INTERVAL_MS + 1,
      limit: HISTORY_SAMPLE_COUNT,
    });
    expect(page.points.map((point) => point.serverTs)).toEqual([
      HISTORY_BASE_TS,
      HISTORY_BASE_TS + HISTORY_SAMPLE_INTERVAL_MS,
    ]);
  });

  it('limit 越界被收敛，LIMIT -1 不会退化为全量查询', async () => {
    const page = await repos.locations.queryHistory({
      deviceId: deviceB,
      from: HISTORY_BASE_TS,
      to: HISTORY_WINDOW_END_TS,
      limit: -1,
    });
    expect(page.points).toHaveLength(1);
    expect(page.truncated).toBe(true);
  });
});

describe('deleteOlderThan 分批删除', () => {
  it('删除 cutoff 之前的点并保留其余', async () => {
    const deleted = await repos.locations.deleteOlderThan(new Date(RETENTION_CUTOFF_TS));
    expect(deleted).toBeGreaterThan(0);

    const page = await repos.locations.queryHistory({
      deviceId: deviceB,
      from: HISTORY_BASE_TS,
      to: HISTORY_WINDOW_END_TS,
      limit: HISTORY_SAMPLE_COUNT,
    });
    expect(page.points.every((point) => point.serverTs >= RETENTION_CUTOFF_TS)).toBe(true);
  });
});

describe('好友授权与拉黑', () => {
  it('未成为好友时不可查看设备', () => {
    expect(repos.friends.canViewDevice(userB, deviceA)).toBe(false);
  });

  it('接受好友申请后可查看', () => {
    const request = repos.friends.createRequest(userB, userA);
    const result = repos.friends.accept(request.id);
    expect(result.accepted).toBe(true);
    expect(repos.friends.areFriends(userA, userB)).toBe(true);
    expect(repos.friends.listFriendIds(userA)).toContain(userB);
    expect(repos.friends.canViewDevice(userB, deviceA)).toBe(true);
  });

  it('目标关闭分享后不可查看', () => {
    repos.users.setShareEnabled(userA, false);
    expect(repos.friends.canViewDevice(userB, deviceA)).toBe(false);
    repos.users.setShareEnabled(userA, true);
    expect(repos.friends.canViewDevice(userB, deviceA)).toBe(true);
  });

  it('拉黑后解除好友且不可查看', () => {
    repos.friends.block(userB, userA);
    expect(repos.friends.areFriends(userA, userB)).toBe(false);
    expect(repos.friends.canViewDevice(userB, deviceA)).toBe(false);
    expect(repos.friends.isBlockedEither(userA, userB)).toBe(true);

    repos.friends.unblock(userB, userA);
    expect(repos.friends.isBlockedEither(userA, userB)).toBe(false);
  });

  it('重复 pending 申请被唯一索引拒绝', () => {
    repos.friends.createRequest(userB, userA);
    expect(() => repos.friends.createRequest(userB, userA)).toThrow();
  });
});

describe('撤销设备与级联删除', () => {
  it('revoke 递增 token_version', () => {
    const before = repos.devices.findById(deviceA);
    repos.devices.revoke(deviceA);
    const after = repos.devices.findById(deviceA);
    expect(after?.status).toBe('revoked');
    expect(after?.tokenVersion).toBe((before?.tokenVersion ?? 0) + 1);
  });

  it('删除用户级联清理设备与位置', () => {
    const deviceId = repos.devices.create({ userId: userB, name: 'Bob Extra' }).id;
    repos.users.delete(userB);

    expect(repos.devices.findById(deviceId)).toBeNull();
    const remaining = db
      .prepare('SELECT COUNT(*) AS n FROM locations WHERE device_id = ?')
      .get(deviceId) as { n: number };
    expect(remaining.n).toBe(0);
  });
});
