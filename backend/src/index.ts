/**
 * TuneMind Backend — Entry point
 *
 * Phase 1: Express skeleton with a single route stub.
 * Phase 2: Firebase Admin SDK initialised, full event ingestion implemented.
 */

import './bootstrap';

import express from 'express';
import { requestLogger } from './middleware/requestLogger';
import eventsRouter from './api/v1/events';
import analyticsRouter from './api/v1/analytics';

const app = express();
const PORT = process.env['PORT'] ? parseInt(process.env['PORT'], 10) : 3000;
const HOST = process.env['HOST'] ?? '127.0.0.1';

// ─── Middleware ───────────────────────────────────────────────────────────────
app.use(express.json({ limit: '1mb' }));
app.use(requestLogger);

// ─── Routes ──────────────────────────────────────────────────────────────────
app.use('/api/v1/events', eventsRouter);
app.use('/api/v1/analytics', analyticsRouter);

app.get('/health', (_req, res) => {
  res.json({
    status: 'ok',
    phase: 2,
    timestamp: new Date().toISOString(),
  });
});

// ─── 404 handler ─────────────────────────────────────────────────────────────
app.use((_req, res) => {
  res.status(404).json({ error: 'Not found' });
});

// ─── Start ───────────────────────────────────────────────────────────────────
app.listen(PORT, HOST, () => {
  console.log(`TuneMind backend listening on ${HOST}:${PORT} (Phase 2 — validated ingestion with SQLite persistence)`);
});

export default app;
