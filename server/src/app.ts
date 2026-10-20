import Fastify, { type FastifyError, type FastifyInstance } from 'fastify';
import type { Config } from './config.js';
import { closeDatabase, openDatabase, type Db } from './db/connection.js';
import { newId } from './db/ids.js';
import { createRepositories, type Repositories } from './db/repositories/index.js';
import { registerHealthRoutes } from './http/health.js';

export interface AppContext {
  app: FastifyInstance;
  db: Db;
  repositories: Repositories;
  config: Config;
}

const ERROR_CODES: Record<number, string> = {
  400: 'INVALID_PAYLOAD',
  401: 'AUTH_INVALID',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  409: 'CONFLICT',
  422: 'INVALID_PAYLOAD',
  429: 'RATE_LIMITED',
};

function errorPayload(requestId: string, code: string, message: string) {
  return { error: { code, message, requestId } };
}

/** 统一错误响应为 protocol.md §2.2 的 { error: { code, message, requestId } }。 */
function registerErrorHandlers(app: FastifyInstance): void {
  app.setNotFoundHandler((request, reply) =>
    reply.code(404).send(errorPayload(request.id, 'NOT_FOUND', 'Route not found')),
  );

  app.setErrorHandler((error: FastifyError, request, reply) => {
    const status =
      error.statusCode && error.statusCode >= 400 && error.statusCode < 600
        ? error.statusCode
        : 500;
    if (status >= 500) {
      request.log.error({ err: error }, 'request failed');
    }
    const code = status >= 500 ? 'INTERNAL' : (ERROR_CODES[status] ?? 'INVALID_PAYLOAD');
    const message = status >= 500 ? 'Internal server error' : error.message;
    return reply.code(status).send(errorPayload(request.id, code, message));
  });
}

/**
 * 构建 Fastify 实例并打开唯一的 SQLite 连接。
 * 单实例单进程：数据库连接不做池化，DB 文件放本地卷（禁用 NFS/SMB）。
 */
export async function buildApp(config: Config): Promise<AppContext> {
  const app = Fastify({
    logger: { level: config.LOG_LEVEL },
    trustProxy: true,
    genReqId: () => `req_${newId().replaceAll('-', '')}`,
  });

  const db = openDatabase(config.DATABASE_PATH);
  const repositories = createRepositories(db);

  registerErrorHandlers(app);
  registerHealthRoutes(app, db);

  app.addHook('onClose', async () => {
    closeDatabase(db);
  });

  return { app, db, repositories, config };
}
