# risu-tokenizer-service

RisuAI 토크나이저를 독립 서비스로 분리한 PoC. Spring WebFlux + Caffeine(L1) + Redis(L2) 2계층 캐시.

## 구조

```
server-webflux/   WebFlux 서비스 (Kotlin)
server-node/      벤치마크용 Node 기준 서버 (RisuAI의 WASM 라이브러리 그대로 사용)
benchmark/        k6 스크립트 + 결과
models/           토크나이저 모델 파일 (RisuAI public/token/, src/etc/ 복사본)
```

## 실행

Redis 필요 (L2 캐시).

```bash
redis-server
cd server-webflux && ./gradlew bootRun    # :8080
cd server-node && npm install && node server.mjs   # :8081 (벤치마크 기준)
```

## API

```
POST /api/tokenize        {text, tokenizer, mode: IDS|COUNT}
POST /api/tokenize/batch  {texts[], tokenizer, mode}
GET  /api/tokenizers
```

```bash
curl -X POST localhost:8080/api/tokenize -H 'Content-Type: application/json' \
  -d '{"text":"Hello world","tokenizer":"cl100k_base"}'
# {"tokenizer":"cl100k_base","modelVersion":"jtokkit-1.1.0","count":2,"ids":[9906,1917],"cache":"COMPUTE"}
```

응답의 `cache` 값으로 응답 계층 확인: `COMPUTE | L1 | L2`

과부하 시 동작: CPU 바운드 연산은 전용 고정 스레드 풀(8스레드)로 격리하며, 유한 큐(2,000건) 포화 시 503, 큐 대기 5초 초과 시 503을 반환해 요청을 차단한다.

## 토크나이저

| name | 백엔드 |
|---|---|
| cl100k_base, o200k_base | jtokkit |
| llama3, claude, deepseek | DJL tokenizers (tokenizer.json 직접 로드) |

## 테스트

```bash
cd server-webflux && ./gradlew test
```

- `GoldenCompatibilityTest`: 70 케이스(14문장 × 5토크나이저), RisuAI WASM 출력과 토큰 단위 동일 검증. 픽스처는 `server-node/scripts/generate-golden.mjs`로 생성. 512토큰 초과 긴 텍스트 포함(DJL 잘림 회귀 방지)
- `CacheKeyTest`: 캐시 키 정합성
- `TokenizeApiIntegrationTest`: COMPUTE→L1 전이, 배치, 오류 처리

## 벤치마크

동일 API를 Node(Express + tiktoken WASM)로 구현해 k6로 비교. 자세한 수치는 `benchmark/RESULTS.md`.

83KB 텍스트(16K 토큰), VU=100:

| 시나리오 | RPS | p99 |
|---|---|---|
| WebFlux (L1 캐시 흡수) | 3,932 | 107ms |
| WebFlux · 캐시 미스 (순수 연산) | 2,716 | 56ms |
| Node (캐시 없음) | 105 | 8.07s |
| Node + LRU (동일 캐시) | 3,251 | 34ms |

37.3배는 캐시 미적용 Node(원래 아키텍처) 기준 시스템 수준 수치다. 캐시 대 캐시로는 1.2배, 순수 연산(양쪽 캐시 없음) E2E로는 25.7배, 코어당 연산 격차는 6.9배(824 vs 119)다.

재현: `cd benchmark && VUS=100 DURATION=15s ./run-benchmark.sh` — Node LRU 행은 `cd server-node && MODE=lru node server.mjs`로 기동 후 동일 부하로 측정한다.
