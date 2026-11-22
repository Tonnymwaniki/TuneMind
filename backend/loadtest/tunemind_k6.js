import http from 'k6/http';
import { check, sleep } from 'k6';

/**
 * TuneMind Pre-Launch Load Test (1,000 Concurrent Virtual Users)
 *
 * Usage:
 *   k6 run -e BASE_URL=https://tunemind.co -e AUTH_JWT=<supabase-jwt> backend/loadtest/tunemind_k6.js
 */
export const options = {
  stages: [
    { duration: '30s', target: 100 },
    { duration: '1m', target: 500 },
    { duration: '2m', target: 1000 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<800', 'p(99)<1500'],
    http_req_failed: ['rate<0.02'],
  },
};

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:3000').replace(/\/+$/, '');
const AUTH_JWT = __ENV.AUTH_JWT || '';

function authHeaders() {
  const headers = { 'Content-Type': 'application/json' };
  if (AUTH_JWT) {
    headers['Authorization'] = `Bearer ${AUTH_JWT}`;
  }
  return headers;
}

export default function () {
  // 1. Health & Public Plans (cached)
  const healthRes = http.get(`${BASE_URL}/health`);
  check(healthRes, { 'health 200': (r) => r.status === 200 });

  const plansRes = http.get(`${BASE_URL}/api/v1/billing/plans`);
  check(plansRes, { 'plans 200': (r) => r.status === 200 });

  // 2. Open-Catalog Track Search (cached)
  const searchQueries = ['sauti sol', 'burna boy', 'wakadinali', 'nyashinski', 'the weeknd'];
  const q = searchQueries[__VU % searchQueries.length];
  const searchRes = http.get(`${BASE_URL}/api/v1/music/search?q=${encodeURIComponent(q)}&limit=8`);
  check(searchRes, { 'search 200 or 429': (r) => r.status === 200 || r.status === 429 });

  // 3. Authenticated Track Cards Snapshot & Sync (when AUTH_JWT provided)
  if (AUTH_JWT) {
    const snapRes = http.get(`${BASE_URL}/api/v1/track-cards/snapshot?limit=100&offset=0`, {
      headers: authHeaders(),
    });
    check(snapRes, {
      'snapshot 200 or 429': (r) => r.status === 200 || r.status === 429,
    });

    // 4. AI Reasoning / Daily Drop Check (10% of iterations to match realistic traffic)
    if (__ITER % 10 === 0) {
      const dropRes = http.post(
        `${BASE_URL}/api/v1/discovery/daily-drop`,
        JSON.stringify({ timezone: 'Africa/Nairobi', topArtists: ['Sauti Sol'] }),
        { headers: authHeaders() }
      );
      check(dropRes, {
        'daily-drop 200/402/429': (r) => r.status === 200 || r.status === 402 || r.status === 429,
      });
    }
  }

  sleep(1);
}
