/**
 * Route-level tests for GET /api/v1/analytics/summary.
 *
 * These tests exercise the Express middleware layer (NODE_ENV guard) and
 * verify that the guard works independently of the AnalyticsService.
 */

import request from 'supertest';
import express from 'express';

// We import the router directly so we can test it without starting the full server.
// The router uses analyticsService internally, which reads from a real SQLite store.
import analyticsRouter from './analytics';

function createApp(): express.Express {
  const app = express();
  app.use('/api/v1/analytics', analyticsRouter);
  return app;
}

const ORIGINAL_NODE_ENV = process.env['NODE_ENV'];

afterEach(() => {
  process.env['NODE_ENV'] = ORIGINAL_NODE_ENV;
});

describe('GET /api/v1/analytics/summary — NODE_ENV guard', () => {

  test('returns 200 in development mode', async () => {
    process.env['NODE_ENV'] = 'development';
    const app = createApp();
    const res = await request(app).get('/api/v1/analytics/summary');
    expect(res.status).toBe(200);
    expect(res.body).toHaveProperty('totalEvents');
  });

  test('returns 403 when NODE_ENV is not development', async () => {
    process.env['NODE_ENV'] = 'production';
    const app = createApp();
    const res = await request(app).get('/api/v1/analytics/summary');
    expect(res.status).toBe(403);
    expect(res.body).toHaveProperty('error');
    expect(res.body.error).toContain('development');
  });

  test('returns 403 when NODE_ENV is undefined', async () => {
    delete process.env['NODE_ENV'];
    const app = createApp();
    const res = await request(app).get('/api/v1/analytics/summary');
    expect(res.status).toBe(403);
  });

  test('userId=all does not bypass the NODE_ENV guard', async () => {
    process.env['NODE_ENV'] = 'production';
    const app = createApp();
    const res = await request(app).get('/api/v1/analytics/summary?userId=all');
    expect(res.status).toBe(403);
  });
});

describe('GET /api/v1/analytics/sessions — NODE_ENV guard', () => {

  test('returns 200 in development mode', async () => {
    process.env['NODE_ENV'] = 'development';
    const app = createApp();
    const res = await request(app).get('/api/v1/analytics/sessions');
    expect(res.status).toBe(200);
    expect(res.body).toHaveProperty('observerSessions');
  });

  test('returns 403 when NODE_ENV is not development', async () => {
    process.env['NODE_ENV'] = 'production';
    const app = createApp();
    const res = await request(app).get('/api/v1/analytics/sessions');
    expect(res.status).toBe(403);
    expect(res.body).toHaveProperty('error');
  });

  test('returns 403 when NODE_ENV is undefined', async () => {
    delete process.env['NODE_ENV'];
    const app = createApp();
    const res = await request(app).get('/api/v1/analytics/sessions');
    expect(res.status).toBe(403);
  });

  test('userId=all does not bypass the NODE_ENV guard for sessions', async () => {
    process.env['NODE_ENV'] = 'production';
    const app = createApp();
    const res = await request(app).get('/api/v1/analytics/sessions?userId=all');
    expect(res.status).toBe(403);
  });
});

describe('GET /api/v1/analytics/timeline — NODE_ENV guard', () => {

  test('returns 200 in development mode', async () => {
    process.env['NODE_ENV'] = 'development';
    const app = createApp();
    const res = await request(app).get('/api/v1/analytics/timeline');
    expect(res.status).toBe(200);
    expect(res.body).toHaveProperty('episodes');
  });

  test('returns 403 when NODE_ENV is not development', async () => {
    process.env['NODE_ENV'] = 'production';
    const app = createApp();
    const res = await request(app).get('/api/v1/analytics/timeline');
    expect(res.status).toBe(403);
  });

  test('returns 403 when NODE_ENV is undefined', async () => {
    delete process.env['NODE_ENV'];
    const app = createApp();
    const res = await request(app).get('/api/v1/analytics/timeline');
    expect(res.status).toBe(403);
  });

  test('userId=all does not bypass the NODE_ENV guard for timeline', async () => {
    process.env['NODE_ENV'] = 'production';
    const app = createApp();
    const res = await request(app).get('/api/v1/analytics/timeline?userId=all');
    expect(res.status).toBe(403);
  });
});
