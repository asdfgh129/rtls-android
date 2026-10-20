import type { FastifyInstance } from 'fastify';
import type { Db } from '../db/connection.js';
import { pingDatabase } from '../db/connection.js';

/**
 * GET /healthz — 见 protocol.md。
 * 无需认证，仅内网暴露；成功返回 {"status":"ok","db":"ok"}。
 */
export function registerHealthRoutes(app: FastifyInstance, db: Db): void {
  app.get('/healthz', async (_request, reply) => {
    const dbOk = pingDatabase(db);
    if (!dbOk) {
      return reply.code(503).send({ status: 'degraded', db: 'error' });
    }
    return { status: 'ok', db: 'ok' };
  });
}
