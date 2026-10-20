import type { Db } from '../connection.js';
import { newId, nowMs } from '../ids.js';

export interface UserRow {
  id: string;
  displayName: string;
  friendCode: string;
  shareEnabled: boolean;
  createdAt: number;
  updatedAt: number;
}

interface RawUserRow {
  id: string;
  display_name: string;
  friend_code: string;
  share_enabled: number;
  created_at: number;
  updated_at: number;
}

export interface NewUser {
  displayName: string;
  friendCode: string;
}

function mapUser(row: RawUserRow): UserRow {
  return {
    id: row.id,
    displayName: row.display_name,
    friendCode: row.friend_code,
    shareEnabled: row.share_enabled === 1,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

const SELECT_COLUMNS = 'id, display_name, friend_code, share_enabled, created_at, updated_at';

export class UserRepository {
  constructor(private readonly db: Db) {}

  create(input: NewUser): UserRow {
    const id = newId();
    const ts = nowMs();
    this.db
      .prepare(
        `INSERT INTO users (id, display_name, friend_code, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?)`,
      )
      .run(id, input.displayName, input.friendCode, ts, ts);
    const created = this.findById(id);
    if (!created) {
      throw new Error(`Failed to load user ${id} after insert`);
    }
    return created;
  }

  findById(id: string): UserRow | null {
    const row = this.db
      .prepare(`SELECT ${SELECT_COLUMNS} FROM users WHERE id = ?`)
      .get(id) as RawUserRow | undefined;
    return row ? mapUser(row) : null;
  }

  findByFriendCode(friendCode: string): UserRow | null {
    const row = this.db
      .prepare(`SELECT ${SELECT_COLUMNS} FROM users WHERE friend_code = ?`)
      .get(friendCode) as RawUserRow | undefined;
    return row ? mapUser(row) : null;
  }

  setShareEnabled(id: string, enabled: boolean): void {
    this.db
      .prepare('UPDATE users SET share_enabled = ?, updated_at = ? WHERE id = ?')
      .run(enabled ? 1 : 0, nowMs(), id);
  }

  delete(id: string): void {
    this.db.prepare('DELETE FROM users WHERE id = ?').run(id);
  }
}
