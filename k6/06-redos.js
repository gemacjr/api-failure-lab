// Scenario 06 - ReDoS. A few "attackers" send a 34-char near-miss; 1 canary pings.
// 16 attackers > the CPU cores of most laptops (check yours: sysctl -n hw.ncpu / nproc).
// Broken: each request pins a CPU core for ~15 s under load. The canary survives CPU saturation (the OS
// scheduler still slices it in), but with >= 20 attackers every Tomcat thread is taken and it dies too.
// Fixed: microseconds per request.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, VARIANT, canary } from './lib.js';

export { canary };

const ATTACK = encodeURIComponent(','.repeat(33) + 'x');

export const options = {
  scenarios: {
    attack: { executor: 'constant-vus', vus: 16, duration: '45s', exec: 'attack' },
    canary: { executor: 'constant-vus', vus: 1, duration: '45s', exec: 'canary' },
  },
  thresholds: {
    'http_req_duration{name:canary}': ['p(95)<250'],
  },
};

export function attack() {
  const res = http.get(`${BASE_URL}/scenarios/redos/${VARIANT}?input=${ATTACK}`, {
    tags: { name: 'attack' },
    timeout: '120s',
  });
  check(res, { 'status 200': (r) => r.status === 200 });
}
