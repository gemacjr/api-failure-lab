# 03 — Hikari connection-pool exhaustion

> **Category:** database · **Endpoints:** `GET /scenarios/pool-exhaustion/broken`, `GET /scenarios/pool-exhaustion/fixed` · **Load script:** `k6/03-pool-exhaustion.js`

## Symptom

> "We get random 500s under load. The logs say *Connection is not available, request timed out after
> 3000ms*, so we asked the DBA, but the database is idle."

That's the confusing part: the database has low CPU and hardly any queries running, yet the app can't
get a connection to it.

## Reproduce

```bash
make up && make run

make load S=03 V=broken
make load S=03 V=fixed
```

While the broken run is going, look at the database from a second terminal:

```bash
make psql
```
```sql
select pid, state, now() - xact_start as open_for, left(query, 60) as last_query
from pg_stat_activity
where datname = 'failurelab' and backend_type = 'client backend';
```

## What you'll see

The load is 20 users for 45 s against a pool of 5 connections with a 3 s connection timeout. Each request makes a 2 s downstream call.

| Metric | broken | fixed |
|---|---|---|
| error rate (`http_req_failed`) | **55.7%** (151 / 271) | **0%** (0 / 444) |
| p95 latency | 4.14 s | 2.09 s (the downstream's 2 s) |
| requests/s | 5.6 | 9.4 (the maximum 20 users × 2 s allow) |
| max `hikaricp_connections_pending` | 14 | 11, but each wait is ~1 ms |
| `pg_stat_activity` during a request | 5 × `idle in transaction` | — |

A sample of `pg_stat_activity` taken during a burst of broken requests:

```
 pid |        state        | in_xact |                   q
-----+---------------------+---------+------------------------------------------
  49 | idle in transaction | t       | select count(*) from downstream_call_log
  50 | idle in transaction | t       | select count(*) from downstream_call_log
  ... (5 rows: the whole pool)
```

## Diagnose

1. **Is it the pool?** Grafana → *Hikari connections* (http://localhost:3300). *active* is pinned at
   *max* (5), *pending* is above 0, and *timeouts/s* is above 0. Requests are queuing for a connection and giving up
   after `connection-timeout` (3 s).
2. **What are the connections doing?** Run the `pg_stat_activity` query above. You'll see 5 sessions in
   state **`idle in transaction`**, open for about 2 s, with a trivial `last_query`.
   **This is the smoking gun:** each connection is checked out and has an open transaction, but it
   is not running any SQL. Something is holding it while doing other work.
3. **Who is holding them?** The app log shows Hikari's leak detector (`leak-detection-threshold: 2000`):
   ```
   WARN ... Apparent connection leak detected ... 
       at ...poolexhaustion.CallLogService.brokenCallAndRecord(CallLogService.java:...)
   ```
   The stack trace shows *where the connection was taken*. It's not really a leak, since the connection
   comes back after about 2 s, but the detector points you straight at the code that holds it too long.
4. **Follow a 500 back to its cause.** The response body carries a `correlationId`. Search for it in the app log:
   ```
   ERROR [<correlationId>] ... Unhandled exception
   org.springframework.transaction.CannotCreateTransactionException: Could not open JPA EntityManager for transaction
   ...
   Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available,
       request timed out after 3002ms (total=5, active=5, idle=0, waiting=...)
   ```
5. **Do the maths.** Maximum throughput = pool size ÷ how long each request holds a connection = 5 ÷ 2 s =
   **2.5 requests/s**, however fast the database is. 20 users need far more than that.

## Root cause

`@Transactional` takes a connection from the pool **when the transaction starts** and returns it only **at
commit**. Everything inside the method runs while that connection is held, and that includes the 2 s HTTP call:

```java
@Transactional                                  // connection taken here
public CallLogResult brokenCallAndRecord() {
    int previousCalls = countCalls();           // 1 ms of SQL
    String response = callSlowDownstream();     // 2000 ms of NOT using the connection
    record(response);                           // 1 ms of SQL
    return ...;
}                                               // connection returned here
```

About 2 ms of real database work holds a scarce connection for 2,002 ms.

The same thing happens in other ways:
- **Open-Session-in-View** (`spring.jpa.open-in-view=true`, *Spring Boot's default*) can keep a
  connection bound to the request for the whole request, including the view rendering and any remote calls in the
  controller. That's why Boot logs a warning about it at startup. This lab turns it off.
- `@Transactional` on a controller, or on a method that sends email, publishes to Kafka, calls S3, or does
  slow CPU work.
- Long-running reports or batch jobs sharing the same pool as the API.

## Fix

Keep the transaction as narrow as the database work. **Never do I/O to anything except the database inside a
DB transaction.**

```java
// fixed: no transaction around the remote call
public CallLogResult fixedCallAndRecord() {
    int previousCalls = countCalls();                    // auto-commit: connection returned immediately
    String response = callSlowDownstream();              // no connection held
    tx.executeWithoutResult(status -> record(response)); // short transaction just for the write
    return new CallLogResult(response, previousCalls);
}
```

Each connection is now held for about 1 ms at a time, so 5 connections can serve thousands of requests per second.

**Why "just make the pool bigger" is the wrong fix:** a bigger pool only raises the ceiling
(10 ÷ 2 s = 5 req/s). It also moves the bottleneck to Postgres (`max_connections`, memory per
connection, lock contention between more concurrent transactions), and every app instance multiplies it.
HikariCP's own guidance is that pools should be **small**, roughly `(core_count × 2) + effective_spindle_count`.

**Trade-off to be aware of:** the fixed version is no longer *one atomic unit*. If the write fails, the
remote call has still happened. When that matters, use an outbox table or an idempotency key instead
of stretching the transaction.

## Takeaway

**A pool's throughput = pool size ÷ hold time. Shrink the hold time, not the pool's importance.**

In code review, look for `@Transactional` methods that call HTTP clients, message brokers, file or object
storage, or anything with "sleep", "retry" or "wait" in it. Also check whether `spring.jpa.open-in-view` has been
explicitly set to `false`.

## Further reading

- [HikariCP: About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- [HikariCP configuration: `connectionTimeout`, `leakDetectionThreshold`](https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby)
- [PostgreSQL: `pg_stat_activity`](https://www.postgresql.org/docs/current/monitoring-stats.html#MONITORING-PG-STAT-ACTIVITY-VIEW)
- [Spring Boot: Open EntityManager in View](https://docs.spring.io/spring-boot/reference/data/sql.html#data.sql.jpa-and-spring-data.open-entity-manager-in-view)
