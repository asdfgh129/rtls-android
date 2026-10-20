import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { loadConfig, loadDotEnv, PROJECT_ROOT, resolveDatabasePath } from '../src/config.js';

const baseEnv = {
  DATABASE_PATH: './server/data/rtls.db',
  JWT_SECRET: 'a'.repeat(32),
  PUBLIC_BASE_URL: 'http://127.0.0.1:9099',
};

describe('config', () => {
  it('相对 DATABASE_PATH 按仓库根解析，与执行目录无关', () => {
    const config = loadConfig(baseEnv);
    expect(config.DATABASE_PATH).toBe(resolve(PROJECT_ROOT, 'server/data/rtls.db'));
  });

  it('绝对路径与 :memory: 原样保留', () => {
    expect(resolveDatabasePath('/data/rtls.db')).toBe('/data/rtls.db');
    expect(resolveDatabasePath(':memory:')).toBe(':memory:');
  });

  it('缺少必填项时立即失败', () => {
    expect(() => loadConfig({})).toThrow(/Invalid configuration/);
  });

  it('生产环境拒绝示例 JWT_SECRET', () => {
    expect(() =>
      loadConfig({
        ...baseEnv,
        NODE_ENV: 'production',
        JWT_SECRET: 'change-me-at-least-32-bytes-long-secret',
      }),
    ).toThrow(/placeholder/);
  });

  it('loadDotEnv 回退到仓库根 .env', () => {
    const dir = mkdtempSync(join(tmpdir(), 'rtls-env-'));
    const envFile = join(dir, '.env');
    writeFileSync(envFile, 'RTLS_TEST_ENV_FALLBACK=1\n');

    const loaded = loadDotEnv([join(dir, 'missing.env'), envFile]);
    expect(loaded).toBe(envFile);
    expect(process.env.RTLS_TEST_ENV_FALLBACK).toBe('1');
    delete process.env.RTLS_TEST_ENV_FALLBACK;
  });
});
