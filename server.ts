import express from 'express';
import path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';
import rawEventsRouter from './backend/src/api/v1/events';
import rawAnalyticsRouter from './backend/src/api/v1/analytics';
import rawReasoningRouter from './backend/src/api/v1/reasoning';
import { TelemetryReasoningPoller } from './backend/src/domain/reasoning/TelemetryReasoningPoller';

import fs from 'fs';

process.env['NODE_ENV'] = process.env['NODE_ENV'] || 'development';

// Automatically parse and populate .env if present in workspace root
if (fs.existsSync('.env')) {
  try {
    const envFileContent = fs.readFileSync('.env', 'utf-8');
    for (const rawLine of envFileContent.split('\n')) {
      const line = rawLine.trim();
      if (!line || line.startsWith('#')) continue;
      const eqIdx = line.indexOf('=');
      if (eqIdx > 0) {
        const k = line.slice(0, eqIdx).trim();
        let v = line.slice(eqIdx + 1).trim();
        if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) {
          v = v.slice(1, -1);
        }
        if (!process.env[k]) {
          process.env[k] = v;
        }
      }
    }
  } catch (err) {
    console.warn('[server.ts] Error reading .env file:', err);
  }
}

// Normalize API_KEY and GEMINI_API_KEY
if (process.env.API_KEY && !process.env.GEMINI_API_KEY) {
  process.env.GEMINI_API_KEY = process.env.API_KEY;
} else if (process.env.GEMINI_API_KEY && !process.env.API_KEY) {
  process.env.API_KEY = process.env.GEMINI_API_KEY;
}

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const getRouter = (r: any) => (typeof r === 'function' ? r : r?.default || r);
const eventsRouter = getRouter(rawEventsRouter);
const analyticsRouter = getRouter(rawAnalyticsRouter);
const reasoningRouter = getRouter(rawReasoningRouter);

async function startServer() {
  const app = express();
  // AI Studio requires the dev server to run on port 3000, 0.0.0.0
  const PORT = 3000;
  const HOST = '0.0.0.0';

  app.use(express.json({ limit: '5mb' }));

  // Mount API routes
  app.use('/api/v1/events', eventsRouter);
  app.use('/api/v1/analytics', analyticsRouter);
  app.use('/api/v1/reasoning', reasoningRouter);

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
    // Start background telemetry reasoning poller
    try {
      TelemetryReasoningPoller.getInstance().start(30000);
    } catch (e) {
      console.warn('Could not start TelemetryReasoningPoller:', e);
    }
  });
}

startServer().catch((err) => {
  console.error('Failed to start TuneMind server:', err);
  process.exit(1);
});
