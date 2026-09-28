// Scenario 04 - downstream with no timeout.
// FIRST inject the fault:   make toxic-latency TARGET=downstream MS=30000
// AFTERWARDS remove it:     make toxic-reset
// 30 users + 1 canary. Broken: requests hang for 30s, all 20 threads are stuck, and the canary dies.
// Fixed: ~1s degraded responses and a healthy canary.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, VARIANT, canary } from './lib.js';

export { canary };

export const options = {
  scenarios: {
    load:   { executor: 'constant-vus', vus: 30, duration: '60s', exec: 'load' },
    canary: { executor: 'constant-vus', vus: 1,  duration: '60s', exec: 'canary' },
  },
  thresholds: {
    'http_req_duration{name:canary}': ['p(95)<250'],
    'http_req_duration{name:load}': ['p(95)<1500'],
  },
};

export function load() {
  const res = http.get(`${BASE_URL}/scenarios/downstream-timeout/${VARIANT}`, {
    tags: { name: 'load' },
    timeout: '60s',
  });
  check(res, {
    'status 200': (r) => r.status === 200,
    'degraded': (r) => r.headers['X-Degraded'] === 'true',
  });
}
