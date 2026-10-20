import { buildApp } from './app.js';
import { loadConfig, loadDotEnv } from './config.js';

loadDotEnv();

const config = loadConfig();
const { app } = await buildApp(config);

const shutdown = async (signal: string): Promise<void> => {
  app.log.info({ signal }, 'shutting down');
  try {
    await app.close();
    process.exit(0);
  } catch (error) {
    app.log.error({ error }, 'failed to shut down cleanly');
    process.exit(1);
  }
};

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => {
    void shutdown(signal);
  });
}

try {
  await app.listen({ port: config.PORT, host: '127.0.0.1' });
} catch (error) {
  app.log.error({ error }, 'failed to start server');
  process.exit(1);
}
