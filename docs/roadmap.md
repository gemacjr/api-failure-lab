# Roadmap: phase 2 and beyond

Phase 1 covers one flagship scenario per major category. Each item below follows the same pattern
(`/broken` + `/fixed`, an integration test, a k6 script, and a doc from the template). Items marked 🔧 need new
infrastructure.

## Threads and concurrency

| Scenario | Broken | Fixed |
|---|---|---|
| **Deadlock** | Two endpoints take two locks in opposite order and hang forever under concurrency | A consistent lock order, or `tryLock` with a timeout; the thread dump shows `Found one Java-level deadlock` |
| **Lock contention** | A `synchronized` method around slow work serializes every request | Narrow the critical section, or use concurrent data structures; `BLOCKED` threads in dumps, JFR lock profiling |
| **`@Async` / common-pool starvation** | `CompletableFuture.supplyAsync` without an executor runs blocking I/O on the shared `ForkJoinPool.commonPool` | A dedicated, bounded executor per kind of work |

## Database

| Scenario | Broken | Fixed |
|---|---|---|
| **Missing index** | A filter on an unindexed column of a 1M-row table (sequential scan) | Add an index; read `EXPLAIN ANALYZE` before and after |
| **Row-lock waits** | A long transaction holds `SELECT ... FOR UPDATE` while other requests queue on the same row | A short transaction, `NOWAIT`/`SKIP LOCKED`, or optimistic locking (`@Version`) |
| **Unbounded result set** | `findAll()` on a large table is serialized into one response, causing memory spikes and a slow response | Pagination, or streaming with a cursor |

## Downstream and network

| Scenario | Broken | Fixed |
|---|---|---|
| **Retry storm** | Immediate retries ×3 on every failure triple the load on a struggling dependency | Exponential backoff with jitter, and a retry budget |
| **No circuit breaker** 🔧 | Every request pays the full timeout against a dead dependency | A circuit breaker (Resilience4j: **check Spring Boot 4 compatibility first**) that fails fast and probes for recovery |

## Memory and JVM

| Scenario | Broken | Fixed |
|---|---|---|
| **Allocation churn / GC pauses** | Building huge temporary objects per request (e.g. `String` concatenation in loops, copying lists) | Reuse, streaming, and measuring with JFR allocation profiling |
| **Huge JSON payloads** | Serializing a 50 MB response in memory | Pagination, streaming (`StreamingResponseBody`), field filtering |

## CPU

| Scenario | Broken | Fixed |
|---|---|---|
| **O(n²) algorithm** | `list.contains` inside a loop over the same list | A `HashSet`, and a demonstration of how latency scales with input |
| **Synchronous logging under load** | DEBUG logging with string concatenation to a synchronous appender | Parameterized logging, level guards, an async appender |

## Input and errors

| Scenario | Broken | Fixed |
|---|---|---|
| **Validation → 500** | Invalid input causes a `NullPointerException` deep in the service, returned as a 500 | Bean Validation (`@Valid`) at the edge, returning a 400 `ProblemDetail` |
| **Oversized request body** | No limit on upload or body size, so memory is exhausted | `spring.servlet.multipart.max-*`, `server.tomcat.max-swallow-size`, and streaming |
| **JPA bidirectional recursion** | Serializing an entity with a back-reference causes infinite recursion and a `StackOverflowError` | DTOs at the API boundary, never entities |

## Load and lifecycle

| Scenario | Broken | Fixed |
|---|---|---|
| **Cache stampede** | A popular cache entry expires and 500 concurrent requests all recompute it | Request coalescing (Caffeine `AsyncLoadingCache`), early refresh, and jittered TTLs |
| **No rate limiting** 🔧 | One client consumes all capacity | A token bucket per client (e.g. Bucket4j), returning `429` with `Retry-After` |
| **Graceful shutdown** | A deploy kills in-flight requests, producing 502s at the load balancer | `server.shutdown=graceful`, a readiness probe flip, and a drain timeout |
