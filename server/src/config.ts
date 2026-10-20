import { existsSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';
import { z } from 'zod';

/**
 * 仓库根目录：src/config.ts 与 dist/config.js 都位于 server/ 下一层，
 * 向上两级恒为仓库根。相对 DATABASE_PATH 以此为准，保证
 * `cd server && npm run dev` 与在仓库根执行行为一致。
 */
export const PROJECT_ROOT = resolve(import.meta.dirname, '../..');

const configSchema = z
  .object({
    NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
    PORT: z.coerce.number().int().min(1).max(65535).default(9099),
    DATABASE_PATH: z.string().min(1),
    JWT_SECRET: z.string().min(32),
    LOCATION_RETENTION_DAYS: z.coerce.number().int().positive().default(90),
    PUBLIC_BASE_URL: z.url(),
    LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace']).default('info'),
    STALE_THRESHOLD_MS: z.coerce.number().int().positive().default(120_000),
  })
  .superRefine((config, ctx) => {
    if (config.NODE_ENV === 'production' && config.JWT_SECRET.startsWith('change-me')) {
      ctx.addIssue({
        code: 'custom',
        path: ['JWT_SECRET'],
        message: 'must not use the example placeholder in production',
      });
    }
  });

export type Config = z.infer<typeof configSchema>;

/**
 * 显式加载 .env（若存在）。Node 24 内置 process.loadEnvFile，无需 dotenv 依赖。
 */
export function resolveDatabasePath(raw: string): string {
  if (raw === ':memory:' || isAbsolute(raw)) {
    return raw;
  }
  return resolve(PROJECT_ROOT, raw);
}

/**
 * 依次尝试当前工作目录与仓库根目录的 .env，加载第一个存在的文件。
 * 已存在的环境变量不会被覆盖。
 */
export function loadDotEnv(
  candidates: string[] = [resolve(process.cwd(), '.env'), resolve(PROJECT_ROOT, '.env')],
): string | null {
  const found = candidates.find((path) => existsSync(path));
  if (!found) {
    return null;
  }
  process.loadEnvFile(found);
  return found;
}

/**
 * 启动时校验配置，失败立即抛出，不带着半配置运行。
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const parsed = configSchema.safeParse(env);
  if (!parsed.success) {
    const details = parsed.error.issues
      .map((issue) => `${issue.path.join('.') || '(root)'}: ${issue.message}`)
      .join('; ');
    throw new Error(`Invalid configuration: ${details}`);
  }
  return {
    ...parsed.data,
    DATABASE_PATH: resolveDatabasePath(parsed.data.DATABASE_PATH),
  };
}
