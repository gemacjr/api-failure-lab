import http from 'k6/http';
import { check, sleep } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8180';
export const VARIANT = __ENV.VARIANT || 'broken';

if (VARIANT !== 'broken' && VARIANT !== 'fixed') {
  throw new Error(`VARIANT must be "broken" or "fixed", got "${VARIANT}"`);
}

// The canary: one virtual user pinging an endpoint that does no work. If its latency rises,
// the whole app is starved, not just the endpoint under load.
export function canary() {
  const res = http.get(`${BASE_URL}/api/ping`, { tags: { name: 'canary' } });
  check(res, { 'canary 200': (r) => r.status === 200 });
  sleep(0.5);
}
