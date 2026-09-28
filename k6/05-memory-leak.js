// Scenario 05 - memory leak. A steady 20 req/s, each with a NEW cache key (~2 MB/s of retained heap).
// Broken with -Xmx256m: heap climbs, GC pause time rises, latency spikes, and then OutOfMemoryError
// (heap dump in ./dumps). Fixed: heap levels off at ~50 MB of cache.
// Recover a broken run without restarting: curl -X DELETE localhost:8180/scenarios/memory-leak/broken/cache
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, VARIANT } from './lib.js';

export const options = {
  scenarios: {
    leak: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: '4m',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
};

export default function () {
  const key = `${__VU}-${__ITER}-${Date.now()}`;
  const res = http.get(`${BASE_URL}/scenarios/memory-leak/${VARIANT}?key=${key}`);
  check(res, { 'status 200': (r) => r.status === 200 });
}
