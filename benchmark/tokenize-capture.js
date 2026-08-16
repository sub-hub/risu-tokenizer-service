// k6 benchmark: POST /api/tokenize
//
// Usage:
//   k6 run -e BASE=http://localhost:8080 -e MODE=unique -e TOKENIZER=cl100k_base \
//     -e VUS=50 -e DURATION=30s tokenize.js
//
// MODE=unique -> every request tokenizes a different text (compute-dominated)
// MODE=same   -> every request tokenizes the SAME text (cache-hit dominated)
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const MODE = __ENV.MODE || 'unique';
const TOKENIZER = __ENV.TOKENIZER || 'cl100k_base';

// LEN controls text size: 1 = ~180 chars, 30 = ~5KB, 1000 = ~137KB (compute-dominated)
const LEN = Number(__ENV.LEN || 1);
const UNIT = 'the quick brown fox jumps over the lazy dog while the tokenizer merges byte pairs. ';

// NOTE: texts are generated deterministically per iteration (NO pre-built pool).
// A static pool of 20k x LEN=1000 entries would consume ~2.7GB of k6 VM memory.
function textFor(iter) {
  return MODE === 'same'
    ? 'cached message: ' + UNIT.repeat(LEN)
    : `User message number ${iter}: ` + UNIT.repeat(LEN);
}

export const options = {
  vus: Number(__ENV.VUS || 50),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const text = textFor(__ITER);
  const body = JSON.stringify({ text, tokenizer: TOKENIZER, mode: 'COUNT' });
  const res = http.post(`${BASE}/api/tokenize`, body, {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'status 200': (r) => r.status === 200 });
}

// Emit a clean JSON summary (units in milliseconds) for the benchmark runner.
