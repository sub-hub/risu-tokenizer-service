#!/usr/bin/env bash
# Runs the Node-vs-WebFlux benchmark matrix and appends results to results.csv
#
# Requires:
#   - WebFlux app on :8080 (cd server-webflux && ./gradlew bootRun)
#   - Node baseline on :8081, compute mode (cd server-node && node server.mjs)
#   - k6 on PATH (or set K6=/path/to/k6)
#
# NOTE: the "Node + LRU" row is measured separately — restart the Node server with
#   MODE=lru node server.mjs   (in-process LRU, single-instance analog of Java L1)
#   then run the same `same`-mode scenario against :8081.
set -e
cd "$(dirname "$0")"
K6=${K6:-k6}
VUS=${VUS:-50}
DURATION=${DURATION:-20s}
OUT=results.csv

if [ ! -f "$OUT" ]; then
  echo "scenario,vus,duration,rps,med_ms,p90_ms,p99_ms,avg_ms,failed" > "$OUT"
fi

run_scenario() {
  local script=$1 scenario=$2 base=$3 mode=$4 len=${5:-1}
  rm -f summary.json
  "$K6" run --quiet -e SCENARIO="$scenario" -e BASE="$base" -e MODE="$mode" -e LEN="$len" \
    -e VUS="$VUS" -e DURATION="$DURATION" "$script" > /dev/null 2>&1 || true
  if [ -f summary.json ]; then
    local line; line=$(node -e "
      const s = require('./summary.json');
      const f = (v) => (v == null ? 0 : v);
      console.log([s.scenario, s.vus, s.duration, f(s.rps).toFixed(1), f(s.med_ms).toFixed(2), f(s.p90_ms).toFixed(2), f(s.p99_ms).toFixed(2), f(s.avg_ms).toFixed(2), f(s.failed).toFixed(3)].join(','));
    ")
    echo "$line" >> "$OUT"
    echo "  -> $line"
  else
    echo "  -> FAILED to parse $scenario"
  fi
}

echo "== Java WebFlux (:8080), short texts =="
run_scenario tokenize.js      "java-compute" "http://localhost:8080" "unique" 1
run_scenario tokenize.js      "java-l1"      "http://localhost:8080" "same" 1

echo "== Java WebFlux (:8080), long texts (5KB) =="
run_scenario tokenize.js      "java-compute" "http://localhost:8080" "unique" 30
run_scenario tokenize.js      "java-l1"      "http://localhost:8080" "same" 30

echo "== Java WebFlux (:8080), 83KB texts =="
run_scenario tokenize.js      "java-compute" "http://localhost:8080" "unique" 1000
run_scenario tokenize-miss.js "java-miss"    "http://localhost:8080" "unique" 1000

echo "== Node baseline (:8081), short texts =="
run_scenario tokenize.js      "node-compute" "http://localhost:8081" "unique" 1

echo "== Node baseline (:8081), long texts (5KB) =="
run_scenario tokenize.js      "node-compute" "http://localhost:8081" "unique" 30

echo "== Node baseline (:8081), 83KB texts =="
run_scenario tokenize.js      "node-compute" "http://localhost:8081" "unique" 1000

echo ""
echo "Node + LRU는 별도 측정 (cd server-node && MODE=lru node server.mjs 후 MODE=same 부하)"
echo ""
echo "Results in $OUT:"
awk -F, 'BEGIN{printf "%-13s %-4s %-9s %-10s %-7s %-7s %-7s %-7s %s\n","scenario","vus","dur","rps","med","p90","p99","avg","fail"}{printf "%-13s %-4s %-9s %-10s %-7s %-7s %-7s %-7s %s\n",$1,$2,$3,$4,$5,$6,$7,$8,$9}' "$OUT"
