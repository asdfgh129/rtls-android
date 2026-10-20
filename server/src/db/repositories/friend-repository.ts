import type { Db } from '../connection.js';
import { newId, nowMs } from '../ids.js';
import { writeTransaction } from '../connection.js';
import type { FriendRequestStatus } from './types.js';

export interface FriendRequestRow {
  id: string;
  requesterUserId: string;
  targetUserId: string;
  status: FriendRequestStatus;
  createdAt: number;
  respondedAt: number | null;
}

interface RawFriendRequestRow {
  id: string;
  requester_user_id: string;
  target_user_id: string;
  status: FriendRequestStatus;
  created_at: number;
  responded_at: number | null;
}

export interface AcceptResult {
  accepted: boolean;
  reason?: 'not_found' | 'not_pending';
}

/** friendships 只存 (low, high)，写入前必须规范化。 */
export function normalizePair(a: string, b: string): [low: string, high: string] {
  return a < b ? [a, b] : [b, a];
}

function mapRequest(row: RawFriendRequestRow): FriendRequestRow {
  return {
    id: row.id,
    requesterUserId: row.requester_user_id,
    targetUserId: row.target_user_id,
    status: row.status,
    createdAt: row.created_at,
    respondedAt: row.responded_at,
  };
}

export class FriendRepository {
  constructor(private readonly db: Db) {}

  areFriends(a: string, b: string): boolean {
    if (a === b) {
      return false;
    }
    const [low, high] = normalizePair(a, b);
    const row = this.db
      .prepare('SELECT 1 AS ok FROM friendships WHERE user_low_id = ? AND user_high_id = ?')
      .get(low, high) as { ok: number } | undefined;
    return row?.ok === 1;
  }

  listFriendIds(userId: string): string[] {
    const rows = this.db
      .prepare(
        `SELECT CASE WHEN user_low_id = :userId THEN user_high_id ELSE user_low_id END AS friend_user_id
         FROM friendships
         WHERE user_low_id = :userId OR user_high_id = :userId`,
      )
      .all({ userId }) as { friend_user_id: string }[];
    return rows.map((row) => row.friend_user_id);
  }

  findRequest(id: string): FriendRequestRow | null {
    const row = this.db
      .prepare(
        `SELECT id, requester_user_id, target_user_id, status, created_at, responded_at
         FROM friend_requests WHERE id = ?`,
      )
      .get(id) as RawFriendRequestRow | undefined;
    return row ? mapRequest(row) : null;
  }

  /** 同方向 pending 唯一；若已存在则由 UNIQUE 索引抛错。 */
  createRequest(requesterUserId: string, targetUserId: string): FriendRequestRow {
    if (requesterUserId === targetUserId) {
      throw new Error('Cannot send a friend request to yourself');
    }
    const id = newId();
    this.db
      .prepare(
        `INSERT INTO friend_requests (id, requester_user_id, target_user_id, created_at)
         VALUES (?, ?, ?, ?)`,
      )
      .run(id, requesterUserId, targetUserId, nowMs());
    const created = this.findRequest(id);
    if (!created) {
      throw new Error(`Failed to load friend request ${id} after insert`);
    }
    return created;
  }

  /**
   * 接受好友申请：
   * - 单写事务（BEGIN IMMEDIATE）；
   * - 条件更新，只有仍为 pending 才继续；
   * - 规范化写入 friendships，并取消同方向其他 pending。
   */
  accept(requestId: string): AcceptResult {
    return writeTransaction(this.db, () => {
      const request = this.findRequest(requestId);
      if (!request) {
        return { accepted: false, reason: 'not_found' } as const;
      }
      if (request.status !== 'pending') {
        return { accepted: false, reason: 'not_pending' } as const;
      }

      const [low, high] = normalizePair(request.requesterUserId, request.targetUserId);
      this.db
        .prepare(
          'INSERT INTO friendships (user_low_id, user_high_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING',
        )
        .run(low, high, nowMs());

      const ts = nowMs();
      this.db
        .prepare("UPDATE friend_requests SET status = 'accepted', responded_at = ? WHERE id = ?")
        .run(ts, requestId);

      this.db
        .prepare(
          `UPDATE friend_requests
           SET status = 'cancelled', responded_at = ?
           WHERE status = 'pending' AND id <> ?
             AND ((requester_user_id = ? AND target_user_id = ?)
               OR (requester_user_id = ? AND target_user_id = ?))`,
        )
        .run(
          ts,
          requestId,
          request.requesterUserId,
          request.targetUserId,
          request.targetUserId,
          request.requesterUserId,
        );

      return { accepted: true } as const;
    });
  }

  removeFriend(a: string, b: string): void {
    const [low, high] = normalizePair(a, b);
    this.db.prepare('DELETE FROM friendships WHERE user_low_id = ? AND user_high_id = ?').run(low, high);
  }

  isBlockedEither(a: string, b: string): boolean {
    const row = this.db
      .prepare(
        `SELECT 1 AS ok FROM blocks
         WHERE (blocker_user_id = ? AND blocked_user_id = ?)
            OR (blocker_user_id = ? AND blocked_user_id = ?)`,
      )
      .get(a, b, b, a) as { ok: number } | undefined;
    return row?.ok === 1;
  }

  /** 拉黑：删除好友关系与双方 pending 请求，再写入 blocks（单写事务）。 */
  block(blockerUserId: string, blockedUserId: string): void {
    if (blockerUserId === blockedUserId) {
      throw new Error('Cannot block yourself');
    }
    const [low, high] = normalizePair(blockerUserId, blockedUserId);
    writeTransaction(this.db, () => {
      this.db.prepare('DELETE FROM friendships WHERE user_low_id = ? AND user_high_id = ?').run(low, high);
      this.db
        .prepare(
          `DELETE FROM friend_requests
           WHERE status = 'pending'
             AND ((requester_user_id = ? AND target_user_id = ?)
               OR (requester_user_id = ? AND target_user_id = ?))`,
        )
        .run(blockerUserId, blockedUserId, blockedUserId, blockerUserId);
      this.db
        .prepare(
          'INSERT INTO blocks (blocker_user_id, blocked_user_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING',
        )
        .run(blockerUserId, blockedUserId, nowMs());
    });
  }

  unblock(blockerUserId: string, blockedUserId: string): void {
    this.db
      .prepare('DELETE FROM blocks WHERE blocker_user_id = ? AND blocked_user_id = ?')
      .run(blockerUserId, blockedUserId);
  }

  /**
   * 是否有权查看设备位置（data-model §5.2）。
   * 校验：设备 active + 目标 share_enabled + 互为好友 + 双向无拉黑。
   */
  canViewDevice(viewerUserId: string, targetDeviceId: string): boolean {
    const row = this.db
      .prepare(
        `SELECT 1 AS ok
         FROM devices d
         JOIN users target_user ON target_user.id = d.user_id
         JOIN friendships f
           ON f.user_low_id  = CASE WHEN :viewerUserId < d.user_id THEN :viewerUserId ELSE d.user_id END
          AND f.user_high_id = CASE WHEN :viewerUserId < d.user_id THEN d.user_id ELSE :viewerUserId END
         WHERE d.id = :deviceId
           AND d.status = 'active'
           AND target_user.share_enabled = 1
           AND NOT EXISTS (
               SELECT 1 FROM blocks b
               WHERE (b.blocker_user_id = :viewerUserId AND b.blocked_user_id = d.user_id)
                  OR (b.blocker_user_id = d.user_id AND b.blocked_user_id = :viewerUserId)
           )`,
      )
      .get({ viewerUserId, deviceId: targetDeviceId }) as { ok: number } | undefined;
    return row?.ok === 1;
  }
}
