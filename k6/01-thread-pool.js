// Scenario 01 - Tomcat thread-pool exhaustion.
// 40 users hammer a 2s endpoint (Tomcat has 20 threads) while 1 canary pings /api/ping.
// Watch the canary threshold: it fails for "broken" and passes for "fixed".
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, VARIANT, canary } from './lib.js';

export { canary };

export const options = {
  scenarios: {
    load:   { executor: 'constant-vus', vus: 40, duration: '60s', exec: 'load' },
    canary: { executor: 'constant-vus', vus: 1,  duration: '60s', exec: 'canary' },
  },
  thresholds: {
    'http_req_duration{name:canary}': ['p(95)<250'],
  },
};

export function load() {
  const res = http.get(`${BASE_URL}/scenarios/thread-pool/${VARIANT}`, { tags: { name: 'load' } });
  check(res, {
    'served (200)': (r) => r.status === 200,
    'shed fast (503)': (r) => r.status === 503,
  });
}
