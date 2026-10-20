import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { buildApp, type AppContext } from '../src/app.js';
import { loadConfig } from '../src/config.js';

let tempDir = '';
let ctx: AppContext;

beforeAll(async () => {
  tempDir = mkdtempSync(join(tmpdir(), 'rtls-http-'));
  const config = loadConfig({
    NODE_ENV: 'test',
    PORT: '9099',
    DATABASE_PATH: join(tempDir, 'rtls.db'),
    JWT_SECRET: 'b'.repeat(32),
    PUBLIC_BASE_URL: 'http://127.0.0.1:9099',
    LOG_LEVEL: 'fatal',
  });
  ctx = await buildApp(config);
});

afterAll(async () => {
  await ctx.app.close();
  rmSync(tempDir, { recursive: true, force: true });
});

describe('HTTP 契约', () => {
  it('GET /healthz 返回 {status,db}', async () => {
    const response = await ctx.app.inject({ method: 'GET', url: '/healthz' });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({ status: 'ok', db: 'ok' });
  });

  it('未知路由返回统一错误信封', async () => {
    const response = await ctx.app.inject({ method: 'GET', url: '/nope' });
    expect(response.statusCode).toBe(404);
    const body = response.json();
    expect(body.error.code).toBe('NOT_FOUND');
    expect(body.error.requestId).toMatch(/^req_[0-9a-f]{32}$/);
    expect(typeof body.error.message).toBe('string');
  });
});
