# Failure taxonomy: a mental model for how APIs fail

When an API is "slow" or "down", the cause is almost always one of a few patterns. This page gives you
the map. The scenarios are the territory.

## Where a request spends its time

Follow a single request through a Spring Boot service. **Every step is a place it can wait:**

```
client ─► [1] accept queue ─► [2] Tomcat thread ─► [3] your code (CPU)
                                                     │
                                                     ├─► [4] wait for a DB connection (Hikari pool)
                                                     │       └─► [5] network ─► [6] database work
                                                     │
                                                     └─► [5] network ─► [6] downstream service work

   ...and at any moment: [7] a GC pause freezes all of the above
```

| # | Where the time goes | What saturates | Lab scenario |
|---|---|---|---|
| 1 | Waiting for a free thread | Tomcat's thread pool | 01, 04 |
| 2–3 | CPU inside your code | CPU cores | 06 (ReDoS) |
| 4 | Waiting for a pooled connection | Hikari pool | 03 |
| 5 | Network round trips | Latency × number of round trips | 02 (N+1), 04 |
| 6 | The dependency's own work | The dependency | 04 (slow partner) |
| 7 | Stop-the-world GC pauses | Heap | 05 (memory leak) |

When latency goes up, ask: **which of these steps grew?** Each has a metric in the Grafana dashboard.

## The four ways an API fails

| Class | What happens | Phase 1 | Later phases ([roadmap](roadmap.md)) |
|---|---|---|---|
| **Resource exhaustion** | A finite resource runs out (threads, connections, memory, CPU, file descriptors) and requests queue or fail | 01 threads, 03 connections, 05 memory, 06 CPU | async/common-pool starvation, unbounded result sets |
| **Latency amplification** | One slow thing gets multiplied: by round trips, by retries, or by waiting forever | 02 N+1, 04 no timeout | retry storms, missing index, no circuit breaker |
| **Errors** | Wrong status codes, unhandled exceptions, serialization failures | 03's 500s (handled with `ProblemDetail`) | validation returning 500 instead of 400, JPA recursion, oversized bodies |
| **Correctness under load** | Works for one user, wrong for many | — | races, cache stampede, lost updates |

Exhaustion and amplification feed each other. Scenario 04 (amplification: no timeout) *causes* scenario
01 (exhaustion: every thread stuck). Most real outages are a chain of two or three of these.

## USE and RED: two checklists that cover almost everything

**USE (for every *resource*):** check its **U**tilization, **S**aturation and **E**rrors.

| Resource | Utilization | Saturation (queued work) | Errors | Grafana panel |
|---|---|---|---|---|
| Tomcat threads | busy / max | busy = max, ping latency rising | — | *Tomcat threads: busy vs max* |
| DB connections | active / max | `pending` > 0 | `timeouts/s` | *Hikari connections* |
| Heap | used / max | GC pause time rising | `OutOfMemoryError` | *JVM heap*, *GC pause time* |
| CPU | process CPU | CPU ≈ 1.0 while throughput is low | — | *CPU usage* |
| Slow dependency (bulkhead) | permits in use | available = 0 | rejected/s | *Lab: bulkhead* |

**RED (for every *endpoint*):** check its **R**ate, **E**rrors and **D**uration.
The panels are *Request rate by endpoint and status* (rate and errors) and *Server latency p99* (duration).

RED tells you *that* something is wrong, from the user's point of view. USE tells you *which resource*
is responsible. Incidents usually start in RED and end in USE.

## Little's Law: the one formula worth memorizing

```
L = λ × W
in-flight requests = arrival rate × time each request spends in the system
```

It applies to any stable queue, whatever the distribution:

- **Scenario 01:** 20 req/s × 2 s = **40** requests in flight, but Tomcat has **20** threads. The
  missing 20 wait in the accept queue, and so does every `/api/ping`.
- **Scenario 03:** a pool of 5 connections, each held for 2 s → maximum throughput = 5 ÷ 2 = **2.5 req/s**, however
  fast the database is.
- **Scenario 04:** the downstream goes from 10 ms to 30 s → the same traffic needs **3,000×** more threads.

The practical lesson: **when latency (W) goes up, the concurrency you need (L) goes up by the same factor.** A
slow dependency doesn't only add latency. It *consumes capacity*.

## Cascading failure, and why the canary exists

A cascading failure spreads from one component to another:

1. The partner service slows down (a latency toxic).
2. Your calls to it have no timeout (04), so each request holds a thread for 30 s.
3. The thread pool fills up (01), so *every* endpoint queues, including health checks.
4. Health checks fail, the orchestrator restarts "unhealthy" instances, and the remaining instances get even more load.
5. Your callers, who also have no timeouts, fill *their* pools. The failure moves up the call chain.

That's why the k6 scripts for 01, 04 and 06 include a **canary**: one virtual user pinging `/api/ping`, an
endpoint that does nothing. If the canary slows down, the problem is no longer one endpoint. The whole
process is starved.

## Latency: why p99 and not the average

- **Averages hide pain.** 99 requests at 10 ms and 1 at 10 s average to 110 ms, which "looks fine", while 1 user
  in 100 waits 10 seconds. Scenario 01's broken variant looks like this before it gets worse.
- **Tail latency multiplies.** A page that makes 20 backend calls hits a p95 on at least one of them most of
  the time: 1 − 0.95²⁰ ≈ 64%.
- **So the dashboard shows p99** (`histogram_quantile(0.99, ...)` over Micrometer histograms, enabled with
  `management.metrics.distribution.percentiles-histogram`).

**One load-testing caveat: coordinated omission.** A *closed-loop* load test (k6's `constant-vus`) waits for
each response before sending the next request. When the server stalls, the load generator slows down with it, and it
*under-reports* how bad the stall was. An *open-loop* test (`constant-arrival-rate`, used by scenario 05)
keeps sending at a fixed rate, like real users do. Keep this in mind when you compare numbers.
