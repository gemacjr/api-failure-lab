// Scenario 03 - Hikari pool exhaustion. 20 users, a pool of 5, a 2s downstream call.
// Broken: ~3s waits and then 500s. Fixed: every request ~2s and 200.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, VARIANT } from './lib.js';

export const options = {
  vus: 20,
  duration: '45s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/scenarios/pool-exhaustion/${VARIANT}`);
  check(res, { 'status 200': (r) => r.status === 200 });
}
