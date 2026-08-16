// Node.js baseline for the risu-tokenizer-service benchmark.
//
// Purpose: identical API surface to the WebFlux service (POST /api/tokenize,
// /api/tokenize/batch) but implemented the way RisuAI ships it: WASM tokenizers
// loaded into the process, no reactive pipeline, no distributed cache.
//
// Modes:
//   MODE=compute   (default) every request runs the tokenizer (no cache)
//   MODE=lru       in-memory LRU cache (single-process equivalent of Java L1)
//
// Env:
//   PORT=8081  MODELS_DIR=../models  MODE=compute|lru  LRU_SIZE=10000
import express from 'express';
import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const __dirname = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const MODELS_DIR = resolve(__dirname, process.env.MODELS_DIR || '../models');
const PORT = Number(process.env.PORT || 8081);
const MODE = process.env.MODE || 'compute';
const LRU_SIZE = Number(process.env.LRU_SIZE || 10000);

// ---- tokenizer engines (same libs RisuAI uses) ----
const { Tiktoken } = require('@dqbd/tiktoken');

function loadTiktoken(name) {
  const enc = require(`@dqbd/tiktoken/encoders/${name}.json`);
  // Reuse ONE Tiktoken instance per engine (RisuAI holds a singleton parser).
  const tok = new Tiktoken(enc.bpe_ranks, enc.special_tokens, enc.pat_str);
  return { name, version: `tiktoken-wasm-1.0.22:${name}`, encode: (t) => Array.from(tok.encode(t)) };
}

const { Tokenizer } = require('./vendor/web-tokenizers.cjs');

async function loadJsonTokenizer(name, file) {
  const buf = readFileSync(file);
  const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
  const tok = await Tokenizer.fromJSON(ab);
  return { name, version: `web-tokenizers-0.1.6:${name}`, encode: (t) => Array.from(tok.encode(t)) };
}

const engines = new Map();
for (const n of ['cl100k_base', 'o200k_base']) engines.set(n, loadTiktoken(n));
for (const [n, f] of [['llama3', 'llama3.json'], ['claude', 'claude.json'], ['deepseek', 'deepseek.json']]) {
  if (existsSync(resolve(MODELS_DIR, f))) {
    engines.set(n, await loadJsonTokenizer(n, resolve(MODELS_DIR, f)));
  }
}

// ---- optional single-process LRU (Node analog of Java L1) ----
const lru = new Map();
function lruGet(key) {
  const v = lru.get(key);
  if (v !== undefined) { lru.delete(key); lru.set(key, v); }
  return v;
}
function lruPut(key, val) {
  lru.delete(key);
  lru.set(key, val);
  if (lru.size > LRU_SIZE) lru.delete(lru.keys().next().value);
}

function tokenize(engine, text) {
  if (MODE === 'lru') {
    const key = `${engine.name}|${engine.version}|${text}`;
    const hit = lruGet(key);
    if (hit !== undefined) return { ids: hit, source: 'L1' };
    const ids = engine.encode(text);
    lruPut(key, ids);
    return { ids, source: 'COMPUTE' };
  }
  return { ids: engine.encode(text), source: 'COMPUTE' };
}

const app = express();
app.use(express.json({ limit: '10mb' }));

app.post('/api/tokenize', (req, res) => {
  const { text = '', tokenizer = 'cl100k_base', mode = 'IDS' } = req.body ?? {};
  const engine = engines.get(tokenizer);
  if (!engine) return res.status(400).json({ error: `Unknown tokenizer '${tokenizer}'` });
  const { ids, source } = tokenize(engine, text);
  res.json({
    tokenizer: engine.name,
    modelVersion: engine.version,
    count: ids.length,
    ids: mode === 'IDS' ? ids : null,
    cache: source,
  });
});

app.post('/api/tokenize/batch', (req, res) => {
  const { texts = [], tokenizer = 'cl100k_base', mode = 'IDS' } = req.body ?? {};
  const engine = engines.get(tokenizer);
  if (!engine) return res.status(400).json({ error: `Unknown tokenizer '${tokenizer}'` });
  res.json({
    tokenizer: engine.name,
    modelVersion: engine.version,
    results: texts.map((t, i) => {
      const { ids, source } = tokenize(engine, t);
      return { index: i, count: ids.length, ids: mode === 'IDS' ? ids : null, cache: source };
    }),
  });
});

app.get('/api/tokenizers', (_req, res) => {
  res.json({ tokenizers: [...engines.keys()].sort() });
});

app.listen(PORT, () => {
  console.log(`[node-baseline] MODE=${MODE} listening on :${PORT}, engines=${[...engines.keys()].join(',')}`);
});
