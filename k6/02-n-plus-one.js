// Scenario 02 - N+1 queries. Same response, 201 SQL statements vs 1.
// Compare http_req_duration between runs, and watch the X-Query-Count header.
// Make it dramatic: `make toxic-latency TARGET=postgres MS=5` adds 5 ms to every DB round trip.
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, VARIANT } from './lib.js';

export const options = {
  vus: 10,
  duration: '30s',
};

export default function () {
  const res = http.get(`${BASE_URL}/scenarios/n-plus-one/${VARIANT}`);
  check(res, {
    'status 200': (r) => r.status === 200,
    'single query': (r) => r.headers['X-Query-Count'] === '1',
  });
}
