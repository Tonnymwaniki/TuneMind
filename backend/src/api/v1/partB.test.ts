import request from 'supertest';
import express from 'express';
import { createApp } from '../../app';
import { createRateLimiter } from '../../middleware/rateLimiter';
import { clearAuthCacheForTests } from '../../middleware/authMiddleware';

const ORIGINAL_NODE_ENV = process.env['NODE_ENV'];
const ORIGINAL_DISABLE_RL = process.env['DISABLE_RATE_LIMIT'];

afterEach(() => {
  process.env['NODE_ENV'] = ORIGINAL_NODE_ENV;
  process.env['DISABLE_RATE_LIMIT'] = ORIGINAL_DISABLE_RL;
  clearAuthCacheForTests();
});

describe('Part B Production Backend Hardening & Public Routes', () => {
  test('GET /health returns 200 with security headers and no X-Powered-By', async () => {
    const app = createApp();
    const res = await request(app).get('/health');
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('ok');
    expect(res.headers['x-content-type-options']).toBe('nosniff');
    expect(res.headers['x-frame-options']).toBe('DENY');
    expect(res.headers['referrer-policy']).toBe('no-referrer');
    expect(res.headers['x-powered-by']).toBeUndefined();
  });

  test('GET /privacy and GET /terms serve HTML legal pages', async () => {
    const app = createApp();

    const privacyRes = await request(app).get('/privacy');
    expect(privacyRes.status).toBe(200);
    expect(privacyRes.headers['content-type']).toContain('text/html');
    expect(privacyRes.text).toContain('TuneMind Privacy Policy');

    const termsRes = await request(app).get('/terms');
    expect(termsRes.status).toBe(200);
    expect(termsRes.headers['content-type']).toContain('text/html');
    expect(termsRes.text).toContain('TuneMind Terms of Service');
  });

  test('GET /api/v1/app/version returns Android version metadata', async () => {
    const app = createApp();
    const res = await request(app).get('/api/v1/app/version');
    expect(res.status).toBe(200);
    expect(res.body.status).toBe('ok');
    expect(res.body.platform).toBe('android');
    expect(typeof res.body.latest_version).toBe('string');
    expect(typeof res.body.min_supported_version).toBe('string');
    expect(typeof res.body.force_update).toBe('boolean');
  });

  test('POST /api/v1/support/feedback validates message and accepts valid tickets', async () => {
    const app = createApp();

    const badRes = await request(app)
      .post('/api/v1/support/feedback')
      .send({ category: 'Bug Report', message: 'hi' });
    expect(badRes.status).toBe(400);

    const okRes = await request(app)
      .post('/api/v1/support/feedback')
      .send({
        category: 'Bug Report',
        message: 'Spotify sync takes a second attempt after reconnecting.',
        user_email: 'listener@example.com',
        diagnostics: { app_version: '0.1.0', version_code: 1 }
      });
    expect(okRes.status).toBe(201);
    expect(okRes.body.status).toBe('received');
    expect(okRes.body.category).toBe('Bug Report');
    expect(typeof okRes.body.id).toBe('string');
  });

  test('DELETE /api/v1/account and GET /api/v1/account/me reject missing Bearer token with 401', async () => {
    const app = createApp();

    const delRes = await request(app).delete('/api/v1/account');
    expect(delRes.status).toBe(401);

    const meRes = await request(app).get('/api/v1/account/me');
    expect(meRes.status).toBe(401);
  });

  test('DELETE /api/v1/events requires authentication or development mode', async () => {
    const app = createApp();

    process.env['NODE_ENV'] = 'production';
    const prodRes = await request(app).delete('/api/v1/events');
    expect(prodRes.status).toBe(403);

    process.env['NODE_ENV'] = 'development';
    const devRes = await request(app).delete('/api/v1/events');
    expect(devRes.status).toBe(200);
    expect(devRes.body.status).toBe('ok');
  });

  test('createRateLimiter blocks requests past threshold with 429 and Retry-After', async () => {
    delete process.env['DISABLE_RATE_LIMIT'];
    const miniApp = express();
    miniApp.use(createRateLimiter({ windowMs: 60_000, max: 2, keyPrefix: 'test-rl' }));
    miniApp.get('/ping', (_req, res) => res.json({ ok: true }));

    const r1 = await request(miniApp).get('/ping');
    expect(r1.status).toBe(200);
    expect(r1.headers['x-ratelimit-remaining']).toBe('1');

    const r2 = await request(miniApp).get('/ping');
    expect(r2.status).toBe(200);
    expect(r2.headers['x-ratelimit-remaining']).toBe('0');

    const r3 = await request(miniApp).get('/ping');
    expect(r3.status).toBe(429);
    expect(r3.body.error).toBe('Too many requests');
    expect(r3.headers['retry-after']).toBeDefined();
  });
});
