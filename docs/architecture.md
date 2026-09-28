# Architecture

## Components, and why each one exists

| Component | Role in the lab | Why it's there |
|---|---|---|
| **Spring Boot app** (host, :8180) | The API under test. Every scenario has a `/broken` and a `/fixed` endpoint | Runs on the host, not in Docker, so `jcmd`, `jstack`, JFR and your IDE's debugger attach directly |
| **PostgreSQL 17** | The real database for scenarios 02 and 03 | Connection pools, transactions and round trips behave as they do in production. An in-memory DB would hide them |
| **WireMock** | The "downstream" partner service: `/slow` (2 s) and `/fast` | A dependency you control completely |
| **Toxiproxy** | A TCP proxy between the app and **both** dependencies | Injects *real network faults* (latency, black holes, resets, refused connections) without changing any code |
| **Prometheus** | Scrapes `/actuator/prometheus` every 5 s | The metrics an on-call engineer would actually have |
| **Grafana** | The "Failure Lab" dashboard (home page) | One screen showing threads, pools, heap, GC, CPU and latency together |
| **k6** | Load generator (`docker compose run k6`) | Failures only appear under concurrency. k6 makes the load repeatable |

### The request path

```
k6 / curl ──► app :8180 ──► Toxiproxy :15432 ──► postgres:5432
                        └─► Toxiproxy :18080 ──► wiremock:8080
```

The app **never** connects to a dependency directly (`application.yml` points at the Toxiproxy ports). That
means any scenario can have a network fault added at any time with `scripts/toxics.sh`, the same way things
break in production: at the network layer, not in your code.

## The deliberate limits

The limits are set **small on purpose**. Failures depend on *ratios* (users per thread, hold time per
connection) rather than absolute numbers. 40 users against 20 threads fails the same way as 4,000 users
against 200 threads, but you don't need a load-testing cluster to see it.

| Setting | Lab value | Typical production value | Makes this visible |
|---|---|---|---|
| `server.tomcat.threads.max` | 20 | 200 (Boot default) | 01 thread-pool exhaustion, 04 cascading stall |
| `server.tomcat.accept-count` | 100 | 100 | where requests queue while all threads are busy |
| Hikari `maximum-pool-size` | 5 | 10 (Hikari default) | 03 pool exhaustion |
| Hikari `connection-timeout` | 3 s | 30 s (Hikari default) | 03: fail in 3 s instead of hanging 30 s |
| Hikari `leak-detection-threshold` | 2 s | off | 03: logs the stack trace of whoever holds a connection too long |
| JVM heap (`bootRun`) | `-Xmx256m` | ¼ of RAM (JVM default) | 05: OOM within minutes, not days |
| `-XX:+HeapDumpOnOutOfMemoryError` | on → `dumps/` | often off (it should be on!) | 05: evidence captured at the moment of death |
| `spring.jpa.open-in-view` | `false` | **`true`** (Boot default) | 02 and 03: lazy loading and connection holding can't hide in the view layer |
| Timed client: connect / read timeout | 500 ms / 1 s | none (JDK `HttpClient` default) | 04: the fix |

## Code layout

```
src/main/java/com/example/failurelab/
  FailureLabApplication.java
  common/                    shared by every scenario - and nothing else is
    ScenarioHeaderFilter       X-Correlation-Id, X-Scenario, X-Variant + logging MDC
    GlobalExceptionHandler     every error -> RFC 9457 ProblemDetail, no stack traces in bodies
    PingController             GET /api/ping - the canary
    ScenarioCatalogController  GET /scenarios
    DownstreamClients          untimedDownstream / timedDownstream RestClients
  scenarios/
    threadpool/        01      Bulkhead, ThreadPoolController
    nplusone/          02      Author, Book, QueryCounter, AuthorReportService
    poolexhaustion/    03      CallLogService (the transaction boundary is the lesson)
    downstreamtimeout/ 04      DownstreamTimeoutController (timeout + fallback)
    memoryleak/        05      MemoryLeakController (static map vs Caffeine)
    redos/             06      CsvRecordValidator, RedosController
src/main/resources/
  application.yml              every deliberate limit, with a comment
  db/migration/                Flyway: V1 authors+books (seeded), V2 downstream_call_log
```

**Rule:** scenario packages never import each other. Each one can be read, and deleted, on its own.

### Adding a scenario

1. Create a package `scenarios/<name>/` with a controller mapped to `/scenarios/<slug>` and a `GET /broken` and
   `GET /fixed` endpoint.
2. Write the integration test **first**: a class extending `AbstractLabIT` that shows the broken symptom
   and the fixed behavior. Watch it fail.
3. Implement until it passes.
4. Add `k6/NN-<slug>.js`, reusing `canary` from `k6/lib.js` if the scenario affects the whole app.
5. Write `docs/scenarios/NN-<slug>.md` from [the template](scenarios/_template.md).
6. Add an entry to `ScenarioCatalogController` and to the table in the README.
7. If it needs new metrics, add a panel to `lab/grafana/dashboards/failure-lab.json`.

## Test strategy

- **Real HTTP, a real Tomcat, a real Postgres.** The tests use `@SpringBootTest(webEnvironment = RANDOM_PORT)` and
  `java.net.http.HttpClient`, **not** MockMvc. MockMvc runs requests on the test thread, so it has no
  Tomcat thread pool to exhaust, and scenarios 01 and 03 could never fail. A test that can't fail proves nothing.
- **Postgres via Testcontainers** (`postgres:17`, the same as the lab). **WireMock in-process** with the same
  `/slow` and `/fast` stubs as the Docker lab (`DownstreamStubs`).
- **One test class per scenario** asserts both the broken *symptom* (it's slow, it fails, it grows) and the fixed
  *behavior*. If a doc claims something, a test proves it.
- **k6 scripts are demonstrations, not tests.** They're for watching the dashboard, and they're not run in CI.
- **One Spring context for all tests** (the shared `AbstractLabIT`), so the whole suite starts Postgres once.
