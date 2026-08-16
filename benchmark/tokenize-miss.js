// k6 benchmark: POST /api/tokenize with GLOBALLY UNIQUE texts (100% cache miss)
//
// Unlike tokenize.js MODE=unique (where __ITER is per-VU so texts repeat across
// VUs and L1 absorbs ~99%), this script keys text on BOTH __VU and __ITER so
// every request tokenizes a different text -> pure compute workload.
//
// Usage:
//   k6 run -e BASE=http://localhost:8080 -e TOKENIZER=cl100k_base \
//     -e VUS=100 -e LEN=1000 -e DURATION=15s tokenize-miss.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const TOKENIZER = __ENV.TOKENIZER || 'cl100k_base';
const LEN = Number(__ENV.LEN || 1);
const UNIT = 'the quick brown fox jumps over the lazy dog while the tokenizer merges byte pairs. ';

export const options = {
  vus: Number(__ENV.VUS || 100),
  duration: __ENV.DURATION || '15s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const text = `User ${__VU} message ${__ITER}: ` + UNIT.repeat(LEN);
  const body = JSON.stringify({ text, tokenizer: TOKENIZER, mode: 'COUNT' });
  const res = http.post(`${BASE}/api/tokenize`, body, {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'status 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const dur = data.metrics.http_req_duration.values;
  const reqs = data.metrics.http_reqs.values;
  return {
    'stdout': '',
    'summary.json': JSON.stringify({
      scenario: __ENV.SCENARIO || 'cache-miss',
      vus: Number(__ENV.VUS || 100),
      duration: __ENV.DURATION || '15s',
      rps: reqs.rate,
      med_ms: dur.med ?? 0,
      p90_ms: dur['p(90)'] ?? 0,
      p99_ms: dur['p(99)'] ?? dur['p(95)'] ?? 0,
      avg_ms: dur.avg ?? 0,
      failed: data.metrics.http_req_failed?.values.rate ?? 0,
    }),
  };
}
