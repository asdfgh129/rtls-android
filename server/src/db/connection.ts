import Database from 'better-sqlite3';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

export type Db = Database.Database;

/**
 * 打开唯一的 SQLite 连接并应用 ADR-009 约定的 PRAGMA。
 * PRAGMA 是连接级设置；foreign_keys / busy_timeout 每次打开都必须重设。
 */
export function openDatabase(databasePath: string): Db {
  if (databasePath !== ':memory:') {
    mkdirSync(dirname(databasePath), { recursive: true });
  }
  const db = new Database(databasePath);
  db.pragma('journal_mode = WAL');
  db.pragma('synchronous = NORMAL');
  db.pragma('foreign_keys = ON');
  db.pragma('busy_timeout = 5000');
  db.pragma('temp_store = MEMORY');
  // 必须在建表前生效；对已存在的库需要先 VACUUM 才能切换。
  db.pragma('auto_vacuum = INCREMENTAL');
  return db;
}

export function pingDatabase(db: Db): boolean {
  const row = db.prepare('SELECT 1 AS ok').get() as { ok: number } | undefined;
  return row?.ok === 1;
}

export function integrityCheck(db: Db): string {
  return db.pragma('integrity_check', { simple: true }) as string;
}

/**
 * 写事务统一使用 BEGIN IMMEDIATE，避免读事务升级写事务时出现 SQLITE_BUSY。
 * SQLite 不支持 SELECT ... FOR UPDATE。
 */
export function writeTransaction<T>(db: Db, fn: () => T): T {
  const tx = db.transaction(fn);
  return tx.immediate();
}

/**
 * 关闭连接前做一次 WAL checkpoint，减少旁路 -wal 文件残留。
 */
export function closeDatabase(db: Db): void {
  try {
    db.pragma('wal_checkpoint(TRUNCATE)');
  } catch (error) {
    // checkpoint 失败不阻塞关闭；由下次启动继续回放 WAL。
    void error;
  }
  db.close();
}
