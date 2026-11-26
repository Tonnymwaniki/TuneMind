import request from 'supertest';
import { createApp } from '../../app';

const app = createApp();

describe('Waitlist routes & API', () => {
  it('serves the waitlist landing page on GET / for HTML clients', async () => {
    const resp = await request(app).get('/').set('Accept', 'text/html');
    expect(resp.status).toBe(200);
    expect(resp.text).toContain('id="waitlist-form"');
    expect(resp.text).toContain('src="/waitlist.js"');
  });

  it('serves waitlist client script on GET /waitlist.js', async () => {
    const resp = await request(app).get('/waitlist.js');
    expect(resp.status).toBe(200);
    expect(resp.headers['content-type']).toContain('javascript');
    expect(resp.text).toContain('/api/v1/waitlist');
  });

  it('rejects invalid email addresses on POST /api/v1/waitlist', async () => {
    const resp = await request(app)
      .post('/api/v1/waitlist')
      .send({ email: 'not-an-email', preferred_player: 'Spotify' });
    expect(resp.status).toBe(400);
    expect(resp.body.ok).toBe(false);
  });

  it('accepts valid signup, returns closed beta links when opted in, and handles duplicates', async () => {
    const first = await request(app)
      .post('/api/v1/waitlist')
      .send({
        email: 'Listener@Example.com',
        preferred_player: 'Boomplay',
        wants_closed_beta: true,
        referral_source: 'tiktok'
      });
    expect(first.status).toBe(201);
    expect(first.body.ok).toBe(true);
    expect(first.body.already_registered).toBe(false);
    expect(first.body.closed_beta_links?.opt_in).toContain('play.google.com/apps/testing/com.tunemind.app');

    const dup = await request(app)
      .post('/api/v1/waitlist')
      .send({
        email: 'listener@example.com',
        preferred_player: 'Spotify',
        wants_closed_beta: false
      });
    expect(dup.status).toBe(200);
    expect(dup.body.ok).toBe(true);
    expect(dup.body.already_registered).toBe(true);

    const stats = await request(app).get('/api/v1/waitlist/stats');
    expect(stats.status).toBe(200);
    expect(stats.body.ok).toBe(true);
    expect(stats.body.count).toBeGreaterThanOrEqual(1);
  });
});
