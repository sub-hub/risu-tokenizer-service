// Golden reference generator: tokenize a corpus with RisuAI's exact WASM libs
// and dump {text, tokenizer, ids} fixtures for the Java golden tests.
//
// Run from the RisuAI repo root (has @dqbd/tiktoken + @mlc-ai/web-tokenizers):
//   node <path>/generate-golden.mjs <models-dir> <out-dir>
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';

const [modelsDirArg, outDirArg] = process.argv.slice(2);
const modelsDir = resolve(modelsDirArg);
const outDir = resolve(outDirArg);
mkdirSync(outDir, { recursive: true });

// Resolve node_modules from the RisuAI repo root (has all WASM deps installed)
const risuRequire = createRequire(resolve('package.json'));
// Script-local files resolve against this script's own location
const localRequire = createRequire(import.meta.url);

// --- corpus: multilingual, special tokens, punctuation, long text ---
const corpus = [
  'Hello world',
  'Hello world! How are you doing today?',
  'The quick brown fox jumps over the lazy dog. 1234567890',
  '안녕하세요, 반갑습니다. 오늘 날씨가 좋네요.',
  'こんにちは、世界。今日はいい天気ですね。',
  '你好，世界。今天天气很好。',
  'Emoji test: 😀🎉🚀🔥💯',
  'Mixed: Hello 안녕 你好 こんにちは 123 🚀',
  'Special chars: \u0000\u0001\u0002\u0003 boundary \u007f\u0080\u00ff',
  'Tabs\tand\nnewlines\r\ncarriage returns',
  'RisuAI is an open-source AI chat platform. ' + 'It supports streaming, memory systems, and plugins. '.repeat(20),
  // Long text: MUST exceed 512 tokens to catch DJL truncation (modelMaxLength)
  'Long input regression: ' + 'the quick brown fox jumps over the lazy dog while the tokenizer merges byte pairs. '.repeat(300),
  'llama3 special: <|begin_of_text|>Hello<|end_of_text|>',
  'claude special: <EOT><META>marker',
];

const loadTiktoken = (name) => {
  const { Tiktoken } = risuRequire('@dqbd/tiktoken');
  const enc = risuRequire(`@dqbd/tiktoken/encoders/${name}.json`);
  return new Tiktoken(enc.bpe_ranks, enc.special_tokens, enc.pat_str);
};

const loadWebTokenizer = async (file) => {
  const { Tokenizer } = localRequire('../vendor/web-tokenizers.cjs');
  const buf = readFileSync(file);
  const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
  return Tokenizer.fromJSON(ab);
};

const results = [];

// tiktoken-based
for (const [name, encName] of [['cl100k_base', 'cl100k_base'], ['o200k_base', 'o200k_base']]) {
  const tok = loadTiktoken(encName);
  for (const text of corpus) {
    results.push({ tokenizer: name, text, ids: Array.from(tok.encode(text)) });
  }
}

// HF tokenizer.json-based
for (const [name, file] of [
  ['llama3', 'llama3.json'],
  ['claude', 'claude.json'],
  ['deepseek', 'deepseek.json'],
]) {
  const tok = await loadWebTokenizer(resolve(modelsDir, file));
  for (const text of corpus) {
    results.push({ tokenizer: name, text, ids: Array.from(tok.encode(text)) });
  }
}

for (const name of ['cl100k_base', 'o200k_base', 'llama3', 'claude', 'deepseek']) {
  const byTok = results.filter((r) => r.tokenizer === name);
  const outFile = resolve(outDir, `${name}.json`);
  writeFileSync(outFile, JSON.stringify(byTok, null, 1));
  console.log(`wrote ${outFile} (${byTok.length} cases)`);
}
