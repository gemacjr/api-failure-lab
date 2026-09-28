# API Failure Lab — Phase 1 Design

**Date:** 2026-09-28
**Status:** Draft, awaiting review

## 1. Purpose

A learning project for finding the ways a Spring Boot API can **fail** (errors) or **slow down**
(latency, throughput collapse). Every failure mode is:

1. **Reproducible on demand.** It has a dedicated endpoint and a load script.
2. **Observable with real tools.** Metrics, logs, thread dumps and heap dumps show it.
3. **Paired with a fix.** A `/broken` and a `/fixed` endpoint sit side by side under the same load.
4. **Documented.** Each has its own page: Symptom → Reproduce → Diagnose → Root cause → Fix → Takeaway.

**Success criterion.** For each scenario, the learner can trigger it, point to the metric or dump that
proves the cause, and explain why the fix works.

**Non-goals:** production readiness, auth, a real domain model, microservices.

## 2. Decisions

| Decision | Choice | Why |
|---|---|---|
| Realism | Real dependencies in Docker | Pool exhaustion, network faults and DB behavior are genuine, not simulated |
| Scenario format | `/broken` + `/fixed` pairs | Before/after comparison under identical load |
| Language / runtime | Java 17 (installed) | No virtual threads (Java 21+), so thread-pool limits behave in the classic way, which is what we want to observe |
| Framework | Spring Boot 4.1.1, Gradle Kotlin DSL (existing wrapper 9.6) | Current GA; keeps the existing project skeleton |
| App runs on | Host, via `./gradlew bootRun` | Easy to attach `jstack`, `jcmd` and JFR, and to restart quickly |
| Dependencies run in | `docker compose` | One command to bring up the lab |

## 3. Architecture

```
                 ┌──────────── docker compose ─────────────────────────────┐
 k6 (load) ──────┼──────────────┐                                          │
                 │              ▼                                          │
                 │   host: Spring Boot app :8080                           │
                 │     │ /scenarios/**   │ /actuator/prometheus            │
                 │     │                 ▲                                 │
                 │     ▼                 │ scrape                          │
                 │  Toxiproxy ────┐   Prometheus :9090 ──► Grafana :3000   │
                 │  :15432 :18080 │                                        │
                 │     │          │                                        │
                 │     ▼          ▼                                        │
                 │  Postgres   WireMock ("downstream service")             │
                 └─────────────────────────────────────────────────────────┘
```

- **Toxiproxy** sits between the app and *both* dependencies. The app never connects to Postgres or
  WireMock directly, so any scenario can have real network faults added (latency, timeouts,
  resets, bandwidth limits) through `scripts/toxics.sh`.
- **WireMock** plays a downstream HTTP service. Its stubs include `/slow` (fixed delay), `/fast`
  and `/flaky` (random 500s).
- **Prometheus** scrapes `host.docker.internal:8080/actuator/prometheus`. **Grafana** is
  provisioned with a datasource and one "Failure Lab" dashboard. The panels are HTTP latency
  p50/p95/p99, request rate by status, Tomcat busy/max threads, Hikari active/pending/timeouts,
  JVM heap and GC pause, process CPU, and outbound HTTP client latency.
- **k6** runs as a compose service under the `tools` profile. `make load S=01 V=broken`.

### Deliberately small limits

The app configures low limits **on purpose**, so failures appear at laptop-scale load. Each
limit is written up in `docs/architecture.md`:

- `server.tomcat.threads.max=20`
- Hikari `maximum-pool-size=5`, `connection-timeout=3s`
- JVM `-Xmx256m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./dumps`
- `spring.jpa.open-in-view=false`. The OSIV trap is covered in the docs; the broken variants use
  explicit transactions so each cause is unambiguous.

## 4. Code layout

```
src/main/java/com/example/failurelab/
  FailureLabApplication.java
  common/            # ScenarioCatalogController (GET /scenarios), PingController (GET /api/ping),
                     # DownstreamClients (RestClient beans: untimed + timed)
  scenarios/
    threadpool/      # 01
    nplusone/        # 02
    poolexhaustion/  # 03
    downstreamtimeout/ # 04
    memoryleak/      # 05
    redos/           # 06
src/main/resources/
  application.yml
  db/migration/      # Flyway: schema + seed (generate_series)
```

Each scenario package holds one `@RestController` exposing `GET /scenarios/{slug}/broken` and
`GET /scenarios/{slug}/fixed`, plus whatever service classes it needs. Slugs: `thread-pool`,
`n-plus-one`, `pool-exhaustion`, `downstream-timeout`, `memory-leak`, `redos`. Scenarios do not import
each other. `common/` is the only shared code.

`GET /api/ping` is a trivial endpoint that works as a **canary**: scenarios 01 and 04 show its
latency degrading even though the load is aimed at a *different* endpoint.

## 5. Phase 1 scenarios

### 01 — Tomcat thread-pool exhaustion (`threadpool`)
- **Broken:** calls WireMock `/slow` (2 s) synchronously on the request thread. At 40 concurrent
  users, all 20 Tomcat threads are blocked, so `/api/ping` queues and its latency jumps from ms to seconds.
- **Fixed:** a **bulkhead** (a `Semaphore` capping concurrent slow calls at 8, `tryAcquire`
  with a 100 ms wait). Excess requests get a fast `503` with `Retry-After`, and the other 12
  threads stay free, so `/api/ping` stays fast.
- **Diagnose:** Tomcat busy threads = max, a thread dump showing threads parked in a socket read, and
  canary p99.

### 02 — N+1 queries (`nplusone`)
- **Data:** `author` (200 rows) 1—* `book` (10 per author), seeded by Flyway.
- **Broken:** `authorRepository.findAll()`, then map each author's lazy `books` → 201 SQL statements.
- **Fixed:** a `@EntityGraph(attributePaths = "books")` or `JOIN FETCH` query → 1 statement.
- **Diagnose:** Hibernate statistics (statement count logged per request), SQL log, and latency
  that grows linearly with row count. The response includes an `X-Query-Count` header, which makes it obvious.

### 03 — Hikari connection-pool exhaustion (`poolexhaustion`)
- **Broken:** a `@Transactional` method reads from the DB and then calls WireMock `/slow` (2 s)
  **while still holding the connection**. At 20 concurrent users with a pool of 5, requests wait
  3 s and then fail with `SQLTransientConnectionException` → 500.
- **Fixed:** do the remote call **outside** the transaction (read → commit → call remote → short
  write transaction). Each connection is held for milliseconds instead of seconds.
- **Diagnose:** `hikaricp_connections_pending`, `hikaricp_connections_timeout_total`, Hikari
  leak-detection log, and `pg_stat_activity` showing `idle in transaction`.

### 04 — Downstream with no timeout (`downstreamtimeout`)
- **Broken:** `RestClient` with no connect/read timeouts calls WireMock `/fast` through
  Toxiproxy. The learner adds a 30 s latency toxic (`scripts/toxics.sh latency downstream 30000`).
  Every request hangs, threads pile up, and the canary dies. This is a cascading failure.
- **Fixed:** connect timeout 500 ms and read timeout 1 s, plus a **fallback**: a degraded `200` with
  `"source": "fallback"` and an `X-Degraded: true` header. The fix changes a slow failure into a fast one.
- **Diagnose:** `http_client_requests_seconds` (the outbound client metric), a thread dump showing
  `SocketInputStream.read`, and Toxiproxy's toxic list.
- **Relation to 01:** 01 isolates the damage, 04 bounds how long it lasts. The docs explain why you
  need both.

### 05 — Memory leak → GC thrash → OOM (`memoryleak`)
- **Broken:** a `static HashMap<String, byte[]>` "cache" stores a 100 KB value for every distinct
  `?key=`. k6 sends unique keys, so the heap climbs, GC pause time rises, throughput collapses, and the
  app ends in `OutOfMemoryError` with a heap dump in `./dumps/`.
- **Fixed:** a Caffeine cache with `maximumSize(500)` and `expireAfterWrite(5m)`, so heap use levels off.
- **Also:** `DELETE /scenarios/memory-leak/broken/cache` clears the leak without a restart.
- **Diagnose:** `jvm_memory_used_bytes{area="heap"}`, `jvm_gc_pause_seconds`, `jcmd <pid>
  GC.class_histogram`, and opening the `.hprof` in Eclipse MAT or VisualVM (walkthrough in the
  toolkit doc).

### 06 — Catastrophic regex backtracking / ReDoS (`redos`)
- **Broken:** validates `?input=` with a nested-quantifier pattern. The pattern is chosen, and
  **verified by a timing test on JDK 17**, to backtrack exponentially: an input of about 30 chars
  plus a trailing mismatch takes seconds of CPU. A handful of requests saturate every core.
- **Fixed:** an equivalent pattern without nested quantifiers (possessive or linear), **plus** a
  max input length checked before any regex runs.
- **Diagnose:** `process_cpu_usage` near 1.0 while throughput is low, and a thread dump where every busy
  thread is inside `java.util.regex.Pattern$...`.

## 6. Error handling

- A global `@RestControllerAdvice` maps exceptions to RFC 9457 `ProblemDetail` responses. It does
  **not** hide the failures: broken endpoints still return their natural 500s and timeouts, so
  learners see realistic behavior. The advice keeps stack traces out of response bodies. They are
  logged instead, with a correlation id.
- Every response carries an `X-Scenario` and `X-Variant` header to make k6 and Grafana filtering easier.

## 7. Testing

Integration tests prove each pair actually behaves as documented. This is important for a learning
project: the docs must not lie.

- **Testcontainers** (Postgres) for 02 and 03. **WireMock** (in-JVM) for 01, 03 and 04.
- One test class per scenario asserts the *broken* symptom and the *fixed* behavior. Examples:
  - 02: query count is 201 for broken and 1 for fixed.
  - 03: broken fails under N concurrent calls; fixed succeeds.
  - 04: broken takes longer than 5 s against a delayed stub; fixed returns the fallback in under 1.5 s.
  - 06: broken takes more than 1 s; fixed takes less than 50 ms; oversized input returns 400.
  - 05: the fixed cache size stays bounded (no OOM test; the OOM is demonstrated manually).
  - 01: with the bulkhead full, the fixed variant returns 503 immediately.
- k6 scripts are **demonstrations**, not CI tests.

## 8. Documentation deliverables

```
README.md                       # what this is, quickstart, lab map, how to run a scenario
docs/architecture.md            # components, ports, the deliberate limits and why
docs/diagnosis-toolkit.md       # jstack/jcmd/JFR, heap dumps + MAT, actuator endpoints,
                                # Grafana panels, pg_stat_activity, toxiproxy CLI
docs/failure-taxonomy.md        # mental model: resource exhaustion / latency / errors / correctness,
                                # the USE & RED methods, "where can a request spend time"
docs/scenarios/_template.md
docs/scenarios/01-thread-pool-exhaustion.md … 06-redos.md
docs/roadmap.md                 # phase 2+ catalog (deadlock, lock contention, async starvation,
                                # missing index, row locks, unbounded results, retry storm,
                                # circuit breaker, GC churn, huge payloads, O(n²), sync logging,
                                # validation 500s, oversized bodies, JPA recursion, cache stampede,
                                # rate limiting, graceful shutdown)
```

Each scenario doc follows the template exactly:
**Symptom · Reproduce (exact commands) · What you'll see (expected numbers) · Diagnose (step by
step) · Root cause · Fix (diff-style broken vs fixed) · Takeaway · Further reading**.

## 9. Developer workflow

```
make up          # docker compose up -d (postgres, wiremock, toxiproxy, prometheus, grafana)
make run         # ./gradlew bootRun with lab JVM flags
make load S=01 V=broken
make toxic-latency TARGET=downstream MS=30000   /   make toxic-reset
make down
```

## 10. Risks

- **The ReDoS pattern might not be catastrophic on JDK 17** because of the JDK's backtracking optimizations.
  Mitigation: the timing test is written first, and the pattern is picked from whatever fails it.
- **Spring Boot 4 module and starter names differ from 3.x.** Mitigation: verify every dependency
  resolves in the first build task before writing any scenario.
- **`host.docker.internal` on Linux** needs `extra_hosts: host-gateway`. It's included in the compose file.
