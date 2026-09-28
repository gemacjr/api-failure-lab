# API Failure Lab

A Spring Boot API **built to break**: a hands-on lab for learning the ways an API fails
(errors) or slows down (latency, throughput collapse), and how to *find* the cause the way you
would during a real incident.

Every failure mode is:

1. **Reproducible on demand.** It has a `/broken` endpoint and a k6 load script.
2. **Observable with real tools.** Grafana dashboards, thread dumps, heap dumps, `pg_stat_activity`.
3. **Paired with a fix.** A `/fixed` endpoint runs under the same load, so you can compare them directly.
4. **Documented.** Each scenario has its own page: Symptom → Reproduce → What you'll see → Diagnose → Root cause → Fix → Takeaway.

**The goal:** for each scenario, you can trigger it, point to the metric or dump that *proves* the cause,
and explain why the fix works.

The dependencies are real (Postgres, and an HTTP service in Docker), and so are the network faults (Toxiproxy).
Nothing is faked with `Thread.sleep()`, so connection pools, socket timeouts and TCP behave exactly as they
do in production.

## Prerequisites

- **Java 17** (the Gradle wrapper handles the rest)
- **Docker** (the lab's dependencies, and Testcontainers for the tests)
- `make` and `curl`
- Optional: [Eclipse MAT](https://eclipse.dev/mat/) for heap dumps, and JDK Mission Control for JFR recordings

## Quickstart

```bash
make up                    # postgres, wiremock, toxiproxy, prometheus, grafana
make run                   # the app on http://localhost:8180 (leave it running)

# in another terminal
open http://localhost:3300 # Grafana - the "Failure Lab" dashboard is the home page
make load S=01 V=broken    # break it...
make load S=01 V=fixed     # ...then compare with the fix
```

`GET http://localhost:8180/scenarios` lists every scenario and its doc.

## Lab map

```
                 ┌──────────── docker compose ─────────────────────────────┐
 k6 (load) ──────┼──────────────┐                                          │
                 │              ▼                                          │
                 │   host: Spring Boot app :8180                           │
                 │     │ /scenarios/**   │ /actuator/prometheus            │
                 │     │                 ▲                                 │
                 │     ▼                 │ scrape                          │
                 │  Toxiproxy ────┐   Prometheus :9190 ──► Grafana :3300   │
                 │  :15432 :18080 │                                        │
                 │     │          │                                        │
                 │     ▼          ▼                                        │
                 │  Postgres   WireMock ("downstream service")             │
                 └─────────────────────────────────────────────────────────┘
```

| What | URL / port | Notes |
|---|---|---|
| The app | http://localhost:8180 | runs on the host (`make run`) |
| Grafana | http://localhost:3300 | anonymous admin; the "Failure Lab" dashboard |
| Prometheus | http://localhost:9190 | `/targets` shows whether the app is being scraped |
| Toxiproxy API | http://localhost:8474 | driven by `scripts/toxics.sh` |
| Postgres, **through Toxiproxy** | `localhost:15432` | what the app uses |
| Postgres, direct | `localhost:55432` | for you (`make psql`); user/password `lab`/`lab` |
| WireMock, **through Toxiproxy** | http://localhost:18080 | what the app uses |
| WireMock, direct | http://localhost:8189 | bypasses the faults |

The host ports avoid the defaults (8080, 3000, 5432, 9090) so that the lab can run alongside other projects.
Everything listens on **127.0.0.1 only**, because a deliberately breakable app shouldn't be reachable from your network.
See [Architecture: Loopback only](docs/architecture.md#loopback-only) (Linux users: read that section).

## Scenarios

| # | Scenario | Category | Symptom |
|---|---|---|---|
| 01 | [Tomcat thread-pool exhaustion](docs/scenarios/01-thread-pool-exhaustion.md) | threads | Everything is slow, even `/api/ping`, although only one feature uses the slow service |
| 02 | [N+1 queries](docs/scenarios/02-n-plus-one.md) | database | Fast on a laptop, slow in production; thousands of tiny identical queries |
| 03 | [Hikari connection-pool exhaustion](docs/scenarios/03-connection-pool-exhaustion.md) | database | Random 500s: "Connection is not available", while the database sits idle |
| 04 | [Downstream call with no timeout](docs/scenarios/04-downstream-no-timeout.md) | downstream | A partner API got slow, and *our* API went down |
| 05 | [Memory leak → GC thrash → OOM](docs/scenarios/05-memory-leak.md) | memory | Slower the longer it runs; a restart "fixes" it; then `OutOfMemoryError` |
| 06 | [Catastrophic regex backtracking (ReDoS)](docs/scenarios/06-redos.md) | cpu | CPU at 100%, throughput near zero, no errors, caused by a handful of requests |

## How to study a scenario

Don't read the whole doc first. That turns an investigation into a recipe.

1. Read only the **Symptom** section.
2. Run the **broken** variant under load.
3. **Try to diagnose it yourself** with [the diagnosis toolkit](docs/diagnosis-toolkit.md). Which Grafana
   panel moves? What do the threads say? What does Postgres say?
4. Then read **Diagnose** and **Root cause**, and compare them with your own findings.
5. Run the **fixed** variant under the same load, and explain *why* each number changed.
6. Read **Takeaway**, and think of where the same bug could be hiding in code you've worked on.

## Make targets

| Target | What it does |
|---|---|
| `make up` | start postgres, wiremock, toxiproxy, prometheus, grafana |
| `make down` | stop everything and delete the volumes |
| `make run` | run the app on the host with the lab JVM flags (`-Xmx256m`, heap dump on OOM) |
| `make test` | run the test suite (needs Docker) |
| `make load S=01 V=broken` | run scenario 01's k6 script against the broken variant |
| `make toxic-latency TARGET=downstream MS=30000` | add latency to `downstream` or `postgres` |
| `make toxic-timeout TARGET=postgres` | black-hole a dependency (the data stops flowing) |
| `make toxic-reset` | remove all network faults |
| `make psql` | open psql straight into Postgres (bypassing Toxiproxy) |
| `make threaddump` | write a thread dump of the running app to `dumps/` |

`scripts/toxics.sh` has more faults: `reset`, `down`, `up`, `list`.

## Running the tests

```bash
make test
```

The tests start Postgres with Testcontainers and an in-process WireMock, then send **real HTTP** to the
app on a random port. Each scenario's test checks that the *broken* variant really shows its symptom and the
*fixed* variant really doesn't. The tests exist to prove that the docs are accurate.

## Documentation

- [Architecture](docs/architecture.md): the components, the deliberate limits, and how to add a scenario
- [Failure taxonomy](docs/failure-taxonomy.md): a mental model for where a request spends its time, and how APIs fail
- [Diagnosis toolkit](docs/diagnosis-toolkit.md): every tool in the lab, with the exact commands and how to read the output
- [Roadmap](docs/roadmap.md): the scenarios planned for later phases
- [Scenario template](docs/scenarios/_template.md)
