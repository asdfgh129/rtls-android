import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { closeDatabase, openDatabase, type Db } from '../src/db/connection.js';

export const MIGRATIONS_DIR = fileURLToPath(new URL('../migrations', import.meta.url));

export interface TempDatabase {
  db: Db;
  dir: string;
  path: string;
  cleanup: () => void;
}

export function createTempDatabase(): TempDatabase {
  const dir = mkdtempSync(join(tmpdir(), 'rtls-test-'));
  const path = join(dir, 'rtls.db');
  const db = openDatabase(path);
  return {
    db,
    dir,
    path,
    cleanup: () => {
      closeDatabase(db);
      rmSync(dir, { recursive: true, force: true });
    },
  };
}

export function migrationFiles(): string[] {
  return readdirSync(MIGRATIONS_DIR)
    .filter((name) => name.endsWith('.sql'))
    .sort();
}

export function parseMigration(sql: string): { up: string; down: string } {
  const up: string[] = [];
  const down: string[] = [];
  let mode: 'up' | 'down' | null = null;

  for (const line of sql.split('\n')) {
    const marker = line.trimStart();
    if (marker.startsWith('-- migrate:up')) {
      mode = 'up';
      continue;
    }
    if (marker.startsWith('-- migrate:down')) {
      mode = 'down';
      continue;
    }
    if (mode === 'up') {
      up.push(line);
    } else if (mode === 'down') {
      down.push(line);
    }
  }

  if (up.length === 0 || down.length === 0) {
    throw new Error('malformed dbmate migration: missing -- migrate:up / -- migrate:down');
  }

  return { up: up.join('\n').trim(), down: down.join('\n').trim() };
}

/** 依次执行所有迁移的 up 段（不依赖 dbmate 二进制）。 */
export function applyMigrations(db: Db): string[] {
  const applied: string[] = [];
  for (const file of migrationFiles()) {
    const sql = readFileSync(join(MIGRATIONS_DIR, file), 'utf8');
    db.exec(parseMigration(sql).up);
    applied.push(file);
  }
  return applied;
}

/** 逆序执行所有迁移的 down 段。 */
export function rollbackMigrations(db: Db): string[] {
  const rolledBack: string[] = [];
  for (const file of [...migrationFiles()].reverse()) {
    const sql = readFileSync(join(MIGRATIONS_DIR, file), 'utf8');
    db.exec(parseMigration(sql).down);
    rolledBack.push(file);
  }
  return rolledBack;
}

export function tableExists(db: Db, name: string): boolean {
  const row = db
    .prepare("SELECT 1 AS ok FROM sqlite_master WHERE type = 'table' AND name = ?")
    .get(name) as { ok: number } | undefined;
  return row?.ok === 1;
}

export function objectExists(db: Db, type: string, name: string): boolean {
  const row = db
    .prepare('SELECT 1 AS ok FROM sqlite_master WHERE type = ? AND name = ?')
    .get(type, name) as { ok: number } | undefined;
  return row?.ok === 1;
}
