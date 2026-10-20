import type { Db } from '../connection.js';
import { newId, nowMs } from '../ids.js';

export interface RefreshTokenRow {
  id: string;
  deviceId: string;
  familyId: string;
  expiresAt: number;
  revokedAt: number | null;
  replacedBy: string | null;
  createdAt: number;
}

interface RawRefreshTokenRow {
  id: string;
  device_id: string;
  family_id: string;
  expires_at: number;
  revoked_at: number | null;
  replaced_by: string | null;
  created_at: number;
}

export interface NewRefreshToken {
  deviceId: string;
  /** SHA-256 摘要，禁止存明文 token。 */
  tokenHash: Buffer;
  familyId?: string;
  expiresAt: number;
}

function mapToken(row: RawRefreshTokenRow): RefreshTokenRow {
  return {
    id: row.id,
    deviceId: row.device_id,
    familyId: row.family_id,
    expiresAt: row.expires_at,
    revokedAt: row.revoked_at,
    replacedBy: row.replaced_by,
    createdAt: row.created_at,
  };
}

const SELECT_COLUMNS =
  'id, device_id, family_id, expires_at, revoked_at, replaced_by, created_at';

export class TokenRepository {
  constructor(private readonly db: Db) {}

  create(input: NewRefreshToken): RefreshTokenRow {
    const id = newId();
    const familyId = input.familyId ?? newId();
    this.db
      .prepare(
        `INSERT INTO refresh_tokens (id, device_id, token_hash, family_id, expires_at, created_at)
         VALUES (?, ?, ?, ?, ?, ?)`,
      )
      .run(id, input.deviceId, input.tokenHash, familyId, input.expiresAt, nowMs());
    const row = this.db
      .prepare(`SELECT ${SELECT_COLUMNS} FROM refresh_tokens WHERE id = ?`)
      .get(id) as RawRefreshTokenRow | undefined;
    if (!row) {
      throw new Error(`Failed to load refresh token ${id} after insert`);
    }
    return mapToken(row);
  }

  /** 仅返回未撤销且未过期的 token。 */
  findActiveByHash(tokenHash: Buffer, at: number = nowMs()): RefreshTokenRow | null {
    const row = this.db
      .prepare(
        `SELECT ${SELECT_COLUMNS} FROM refresh_tokens
         WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?`,
      )
      .get(tokenHash, at) as RawRefreshTokenRow | undefined;
    return row ? mapToken(row) : null;
  }

  revoke(id: string, replacedBy: string | null = null, at: number = nowMs()): void {
    this.db
      .prepare('UPDATE refresh_tokens SET revoked_at = ?, replaced_by = ? WHERE id = ?')
      .run(at, replacedBy, id);
  }

  /** 检测到 token 重用：整族撤销。 */
  revokeFamily(familyId: string, at: number = nowMs()): number {
    const result = this.db
      .prepare('UPDATE refresh_tokens SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL')
      .run(at, familyId);
    return result.changes;
  }

  deleteExpired(at: number = nowMs()): number {
    const result = this.db.prepare('DELETE FROM refresh_tokens WHERE expires_at <= ?').run(at);
    return result.changes;
  }
}
