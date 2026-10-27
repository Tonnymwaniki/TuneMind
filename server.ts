import express from 'express';
import path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';
import rawEventsRouter from './backend/src/api/v1/events';
import rawAnalyticsRouter from './backend/src/api/v1/analytics';

process.env['NODE_ENV'] = process.env['NODE_ENV'] || 'development';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const getRouter = (r: any) => (typeof r === 'function' ? r : r?.default || r);
const eventsRouter = getRouter(rawEventsRouter);
const analyticsRouter = getRouter(rawAnalyticsRouter);

async function startServer() {
  const app = express();
  // AI Studio requires the dev server to run on port 3000, 0.0.0.0
  const PORT = 3000;
  const HOST = '0.0.0.0';

  app.use(express.json({ limit: '5mb' }));

  // Mount API routes
  app.use('/api/v1/events', eventsRouter);
  app.use('/api/v1/analytics', analyticsRouter);

  app.get('/health', (_req, res) => {
    res.json({
      status: 'ok',
      service: 'TuneMind Fullstack Intelligence Platform',
      phase: 2,
      timestamp: new Date().toISOString()
    });
  });

  app.get('/api/download-android', (_req, res) => {
    try {
      execSync('python3 -c "import zipfile, os; zf = zipfile.ZipFile(\'android_update.zip\', \'w\', zipfile.ZIP_DEFLATED); [zf.write(os.path.join(r, f), os.path.relpath(os.path.join(r, f), \'android\')) for r, d, files in os.walk(\'android\') for f in files]; zf.close()"');
    } catch (e) {
      console.error('Error generating android zip:', e);
    }
    const zipPath = path.resolve(process.cwd(), 'android_update.zip');
    res.download(zipPath, 'android_update.zip');
  });


  // Vite integration
  const isDev = process.env['NODE_ENV'] !== 'production';

  if (isDev) {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      server: {
        middlewareMode: true,
        host: '0.0.0.0',
        port: PORT
      },
      appType: 'spa'
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.resolve(__dirname, 'dist');
    app.use(express.static(distPath));
    app.get('*', (_req, res) => {
      res.sendFile(path.resolve(distPath, 'index.html'));
    });
  }

  app.listen(PORT, HOST, () => {
    console.log(`TuneMind server running on http://${HOST}:${PORT} [${process.env['NODE_ENV']}]`);
  });
}

startServer().catch((err) => {
  console.error('Failed to start TuneMind server:', err);
  process.exit(1);
});
