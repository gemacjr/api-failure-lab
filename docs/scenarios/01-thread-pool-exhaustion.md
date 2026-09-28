# 01 — Tomcat thread-pool exhaustion

> **Category:** threads · **Endpoints:** `GET /scenarios/thread-pool/broken`, `GET /scenarios/thread-pool/fixed` · **Load script:** `k6/01-thread-pool.js`

## Symptom

> "Everything got slow — even `/api/ping` and the health checks — but only *one* feature talks to the
> slow partner service."

Endpoints that do no work at all start taking seconds. If an orchestrator (Kubernetes, ECS, a load
balancer) runs health checks against the app, those checks time out, so it restarts or removes
**healthy** instances. That makes the outage worse.

## Reproduce

```bash
make up          # the lab: postgres, wiremock, toxiproxy, prometheus, grafana
make run         # the app, on :8180

make load S=01 V=broken
make load S=01 V=fixed
```

The same thing with nothing but `curl`: start 25 slow calls in the background, then time a ping.

```bash
for i in $(seq 25); do curl -s -o /dev/null localhost:8180/scenarios/thread-pool/broken & done
time curl -s localhost:8180/api/ping      # ~2 s instead of ~2 ms
wait
```

Replace `broken` with `fixed` and run it again: the ping comes back instantly, and 17 of the 25 calls get a `503`.

## What you'll see

k6 runs 40 users against the scenario endpoint plus one canary user on `/api/ping`, for 60 s.
These numbers were measured on the lab machine (10-core Mac, JDK 17, Docker Desktop).

| Metric | broken | fixed |
|---|---|---|
| canary `/api/ping` p95 | **3.56 s** ✗ | **146 ms** ✓ |
| load requests completed in 60 s | 636 (~10/s) | 2,565 (~43/s) |
| load: served (200) | 100% | 9% (240) |
| load: shed fast (503) | 0% | 87% (2,148) |
| Tomcat busy threads (max 20) | 20 | 20 (see below) |

**Why is Tomcat still at 20 busy threads in the fixed run?** A shed request waits up to 100 ms
(`MAX_WAIT_FOR_PERMIT`) for a permit before giving up, and k6 ignores `Retry-After` and retries
*immediately*. So 32 users are constantly spending 100 ms each inside the bulkhead's wait, and that uses up
threads too. The canary still improves 24×, because each thread frees up within 100 ms instead of 2 s.

> 🧪 **Exercise:** predict what happens to *busy threads* and *canary p95* with `MAX_WAIT_FOR_PERMIT = Duration.ZERO`
> (reject instantly) in `ThreadPoolConfig`, then measure it. What do you give up? (Hint: a burst of 9 requests
> arriving within the same millisecond.)

> ℹ️ A handful of `dial: i/o timeout` failures in the k6 output (about one per virtual user, in the first
> second) come from Docker Desktop forwarding a burst of new connections from the k6 container to the host. They are
> not the app: a 40-user run against `/api/ping` shows the same 40 errors out of 144,140 requests.

## Diagnose

Work through this in the order you would during a real incident.

1. **Is it one endpoint, or the whole app?** Grafana → *Server latency p99 by endpoint*
   (http://localhost:3300). If `/api/ping` rises together with the scenario endpoint, the app itself is
   starved. A single slow endpoint would not slow down a no-op endpoint.
2. **Which resource is saturated?** *Tomcat threads: busy vs max*. The *busy* line is pinned at 20, the
   configured maximum. This is **saturation** in the USE method (see `docs/failure-taxonomy.md`).
3. **What are the threads doing?** Take a thread dump while the load is running:
   ```bash
   make threaddump                       # writes dumps/threads-<timestamp>.txt
   grep -A25 '"http-nio-8180-exec' dumps/threads-*.txt \
     | grep -E 'http-nio-8180-exec|State:|ThreadPoolController' | head -40
   ```
   Every `http-nio-8180-exec-*` thread has `ThreadPoolController.callSlowDownstream` on its stack. It is
   parked (`WAITING`/`TIMED_WAITING`) inside the JDK `HttpClient`, waiting for the downstream's response.
   20 threads, all waiting on the same line of code, is the proof.
4. **Do the maths (Little's Law):** in-flight requests = arrival rate × time in system. With 40 users
   looping on a 2 s call, you need ~40 threads in flight, but Tomcat has 20. The other requests, *including every
   ping*, wait in the accept queue (`server.tomcat.accept-count`) for a thread to free up.

## Root cause

The request thread does **blocking I/O**: it sits and waits while the downstream takes 2 s. Tomcat's
pool is fixed at 20 threads and shared by *every* endpoint. Once the slow endpoint holds all 20, new requests
of any kind queue behind it. The slow dependency's latency has become the latency of the whole API.

Nothing here is technically *broken*. No exception is thrown, and every request eventually succeeds. That's
why this failure is so common: in dev, with one user, it is invisible.

## Fix

The fix is to cap how many threads may be inside the slow dependency at once. This is a **bulkhead**, named
after the watertight compartments that stop one leak from sinking the whole ship.

```java
// broken: unlimited - under load, all 20 threads end up here
@GetMapping("/broken")
Map<String, String> broken() {
    return Map.of("downstream", callSlowDownstream());
}

// fixed: at most 8 threads inside the slow call; the rest are turned away in <=100 ms
@GetMapping("/fixed")
ResponseEntity<?> fixed() {
    return bulkhead.tryCall(this::callSlowDownstream)
            .<ResponseEntity<?>>map(body -> ResponseEntity.ok(Map.of("downstream", body)))
            .orElseGet(this::shed);            // 503 + Retry-After: 1
}
```

`Bulkhead.tryCall` is a `Semaphore` with 8 permits:

```java
acquired = permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);  // wait max 100 ms
if (!acquired) return Optional.empty();                                     // full -> shed
try { return Optional.ofNullable(call.get()); }
finally { permits.release(); }                                              // always give it back
```

**Why 8?** At most 8 of the 20 threads can be stuck inside the slow call. The rest can only be held briefly, by the
≤100 ms permit wait (see *What you'll see* for how that still adds up under a retry-happy client). You size a bulkhead from what the
*rest* of the app needs, not from what the slow dependency wants.

**Why a fast 503 beats a slow 200:** a rejected request frees its thread in ≤100 ms. `Retry-After` tells
well-behaved clients when to come back. A slow success holds a scarce resource for 2 s and takes away the
capacity healthy endpoints need.

**Other ways to get the same protection:**
- Resilience4j `Bulkhead` / `ThreadPoolBulkhead`: the production-grade version of this class, with metrics built in.
- A separate executor for the slow call, so the pools are isolated rather than shared.
- **Virtual threads** (Java 21+, `spring.threads.virtual.enabled=true`) remove the 20-thread ceiling, so the
  canary stays fast. **But** now nothing limits how many calls you send to the struggling downstream. You
  have moved the pile-up to *its* thread pool. You still want a concurrency limit.

## Takeaway

**A shared pool is only as fast as its slowest consumer.** Isolate slow dependencies so they can
only use up *their share* of the pool.

In code review, look for a synchronous call to another service, database or file system inside a request
handler with no concurrency limit, especially to a dependency you don't control.

**Relation to scenario 04:** a bulkhead limits *how many* threads a dependency can hold, and a timeout
(scenario 04) limits *for how long*. With only a timeout, 40 req/s × 1 s timeout still pins 40 threads.
With only a bulkhead, the 8 admitted threads can hang forever. You need both.

## Further reading

- Michael Nygard, *Release It!* (2nd ed.): the chapters on the "Bulkheads" and "Blocked Threads" patterns
- [Little's Law](https://en.wikipedia.org/wiki/Little%27s_law)
- [Tomcat HTTP connector: `maxThreads`, `acceptCount`](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html)
- [Resilience4j Bulkhead](https://resilience4j.readme.io/docs/bulkhead)
