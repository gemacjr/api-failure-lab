# 04 — Downstream call with no timeout

> **Category:** downstream · **Endpoints:** `GET /scenarios/downstream-timeout/broken`, `GET /scenarios/downstream-timeout/fixed` · **Load script:** `k6/04-downstream-timeout.js`

## Symptom

> "A partner API got slow and *our* API went down."

At first the error rate barely moves, because requests don't *fail*. They just don't finish. Latency
climbs, then every endpoint stalls (scenario 01), and only then do timeouts and errors appear, usually at
the load balancer rather than in your app.

## Reproduce

```bash
make up && make run

# Make the partner service slow - a REAL network fault, injected by Toxiproxy between app and WireMock
make toxic-latency TARGET=downstream MS=30000

make load S=04 V=broken
make load S=04 V=fixed

make toxic-reset
```

Try the other faults too. They teach different lessons:

| Fault | Command | broken | fixed |
|---|---|---|---|
| Very slow | `./scripts/toxics.sh latency downstream 30000` | hangs ~30 s | fallback after 1 s |
| Black hole (no data at all) | `./scripts/toxics.sh timeout downstream` | hangs **indefinitely** | fallback after 1 s |
| Connection refused | `./scripts/toxics.sh down downstream` | fails **fast** (500) | fallback immediately |
| Connection reset | `./scripts/toxics.sh reset downstream` | fails fast (500) | fallback immediately |

Look at the "Connection refused" row: with a dead dependency, even the broken variant fails quickly.
**A service that is down is kind to you. A service that is slow, or that swallows packets, is cruel,**
and "slow" is how most real dependencies fail.

> Toxiproxy delays *every chunk* of data, so a `latency 1000` toxic can add more than 1 s per request.
> Treat the number as a minimum.

## What you'll see

The load is 30 users plus 1 canary for 60 s, with the downstream +30 s slower.

| Metric | broken | fixed |
|---|---|---|
| requests completed in 60 s | **42** | **1,270** |
| load p95 | 60 s (k6's timeout) | 2.0 s |
| canary `/api/ping` p95 | **30 s** | 528 ms |
| errors | 24% (k6 gave up) | 0% |
| % responses degraded (`X-Degraded: true`) | 0% | 100% |
| Tomcat busy threads (max 20) | 16* | 20 |

The fault table above was verified as well: black hole, broken still hanging when curl gave up at 20 s,
fixed 1.01 s; refused, broken 500 in 13 ms, fixed 3 ms; reset, broken 500 in 15 ms, fixed 7 ms.

Two things in these numbers are worth reading slowly:

- **\*Your monitoring goes blind.** Broken's "16 busy threads" is an *under*-count. Prometheus scrapes
  `/actuator/prometheus`, which needs a Tomcat thread too. While all 20 were stuck, scrapes failed and the
  peak was never recorded. A gap in your graphs during an incident is itself a signal.
- **The fix is a huge improvement, but it's not a complete fix.** Fixed still saturates Tomcat and the canary rises to 528 ms,
  because 30 users × ~1 s timeout ≈ 30 requests in flight against 20 threads (Little's Law). That is exactly
  why a timeout alone isn't enough, and why scenario 01's bulkhead exists (see *Relation to scenario 01*).

## Diagnose

1. **Where is the time going?** Grafana → *Outbound HTTP (downstream) latency p99* (http://localhost:3300)
   climbs to ~30 s, and *Server latency p99* follows it. Your latency is the dependency's latency.
2. **What's saturated?** *Tomcat threads: busy vs max* is pinned at 20, as in scenario 01. The difference is the
   cause: here the dependency got *slower*, while in 01 there was more traffic.
3. **What are the threads doing?**
   ```bash
   make threaddump
   grep -A25 '"http-nio-8180-exec' dumps/threads-*.txt | grep -E 'State:|DownstreamTimeoutController' | sort | uniq -c
   ```
   The request threads are parked inside the JDK `HttpClient`, waiting for a response
   (`jdk.internal.net.http`, `CompletableFuture`), under `DownstreamTimeoutController.broken`.
4. **Confirm what the network is doing:** `./scripts/toxics.sh list` shows the active toxic. In real life
   the equivalent is the partner's status page, their latency on your client metrics
   (`http_client_requests_seconds`), or network monitoring.
5. **Find missing timeouts in code:** search for how HTTP clients are built. A `RestClient`,
   `WebClient`, `HttpClient` or `RestTemplate` built with no `connectTimeout`/`readTimeout` is a finding.

## Root cause

**Most HTTP clients have no read timeout by default.** The JDK `HttpClient` waits forever for a
response. Historically, `HttpURLConnection` and `RestTemplate` did too. Without a timeout, your service
imports the dependency's worst latency *unchanged*. Each stuck request holds a Tomcat thread, and scenario 01
takes over from there: the whole app stalls.

This is a **cascading failure**: one slow service in the call chain takes down every service that calls
it without a timeout, one level at a time.

## Fix

Bound the wait, and decide what to answer when the bound is hit.

```java
// DownstreamClients.java - the "timed" client
HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(500))   // how long to wait to ESTABLISH a connection
        .build();
JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
factory.setReadTimeout(Duration.ofSeconds(1));    // how long to wait for the RESPONSE
```

```java
// DownstreamTimeoutController.fixed()
try {
    String body = timed.get().uri("/fast").retrieve().body(String.class);
    return ResponseEntity.ok(new DownstreamResult("downstream", body));
} catch (ResourceAccessException | HttpServerErrorException ex) {
    // The downstream is unhealthy (timeout, reset, refused, 5xx): degrade instead of failing.
    log.warn("Downstream unavailable ({}); serving fallback", ex.getClass().getSimpleName());
    return ResponseEntity.ok()
            .header("X-Degraded", "true")
            .body(new DownstreamResult("fallback", FALLBACK_VALUE));
}
```

**The decisions in that `catch` block:**
- **Only infrastructure failures fall back.** `ResourceAccessException` covers timeouts, resets and refused
  connections, and `HttpServerErrorException` covers 5xx. A **4xx** (`HttpClientErrorException`) is deliberately *not* caught:
  it means *we* sent a bad request, and a fallback would hide our own bug behind a healthy-looking 200. It
  becomes a 500 through `GlobalExceptionHandler`, and the test `fixedDoesNotMaskClientErrorsFromTheDownstream` pins
  that down.
- **The fallback is marked** with `X-Degraded: true` and `"source":"fallback"`, so clients and dashboards can
  tell real data from a default. An invisible fallback is how stale data goes unnoticed for weeks.
- **Log the exception type, not the stack trace.** During an outage this line runs on every request.

**Choosing the numbers:**
- **Read timeout:** just above the dependency's normal worst case (its p99.9) with some headroom, and **below
  your own caller's timeout**. If your client gives up after 2 s, waiting 5 s for the downstream is wasted
  work. This is called a *timeout budget*.
- **Connect timeout:** short (hundreds of ms). Establishing a TCP connection within a data center takes
  about 1 ms, and if it hasn't happened in 500 ms it won't.

**What this fix does not solve yet** (these are phase-2 scenarios):
- **Retries.** Worth adding for idempotent calls, but only with backoff, jitter and a budget. Otherwise you
  get a retry storm.
- **A circuit breaker.** After many failures in a row, stop calling the dependency for a while and serve the
  fallback immediately, rather than paying the 1 s timeout on every request.

## Takeaway

**Every network call needs a timeout. With no timeout, your latency is your dependency's worst day.**

In code review, look for any HTTP client, database driver, cache client or message-broker client
built without explicit connect and read timeouts. Defaults are often "infinite".

**Relation to scenario 01:** the timeout bounds *how long* each thread is stuck, and the bulkhead bounds *how many*
threads get stuck. At 30 req/s with a 1 s timeout you still tie up about 30 threads during an outage, which is
more than Tomcat has. Real services use both.

## Further reading

- AWS Builders' Library, ["Timeouts, retries, and backoff with jitter"](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/)
- Michael Nygard, *Release It!* (2nd ed.): the chapters on "Integration Points", "Timeouts" and "Cascading Failures"
- [Toxiproxy toxics reference](https://github.com/Shopify/toxiproxy#toxics)
