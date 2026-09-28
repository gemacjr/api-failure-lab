# Diagnosis toolkit

For each tool: **the question it answers, the exact command in this lab, and how to read the output.**
The app runs on the host as `FailureLabApplication`. Most JDK commands need its process id:

```bash
PID=$(jps | awk '/FailureLabApplication/{print $1}'); echo $PID
```

## 1. Grafana and Prometheus: *what is saturated, and since when?*

Open http://localhost:3300 (the "Failure Lab" dashboard is the home page). Every panel is a PromQL query
you can also run in Prometheus at http://localhost:9190/graph:

| Panel | PromQL | Read it as |
|---|---|---|
| Server latency p99 by endpoint | `histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket{uri=~"/scenarios/.*\|/api/ping"}[1m])))` | If `/api/ping` rises with another endpoint, the whole app is starved |
| Request rate by endpoint and status | `sum by (uri, status) (rate(http_server_requests_seconds_count{...}[1m]))` | Throughput and error rate (5xx) |
| Tomcat threads: busy vs max | `sum(tomcat_threads_busy_threads)` vs `sum(tomcat_threads_config_max_threads)` | busy = max means requests are queuing |
| Hikari connections | `hikaricp_connections_active`, `_pending`, `_max`, `rate(hikaricp_connections_timeout_total[1m])` | pending > 0 means waiting for a connection; timeouts mean 500s |
| Outbound HTTP latency p99 | `histogram_quantile(0.99, sum by (le, uri, outcome) (rate(http_client_requests_seconds_bucket[1m])))` | The dependency's latency, as your app sees it |
| JVM heap used vs max | `sum(jvm_memory_used_bytes{area="heap"})` vs `sum(jvm_memory_max_bytes{area="heap"})` | A rising *floor* after each GC means a leak |
| GC pause time per second | `sum by (action, cause) (rate(jvm_gc_pause_seconds_sum[1m]))` | Seconds of pause per second: approaching 1 means GC thrash |
| CPU usage | `process_cpu_usage`, `system_cpu_usage` | High CPU with low throughput means pathological work |
| Lab: bulkhead | `lab_bulkhead_available`, `rate(lab_bulkhead_rejected_total[1m])` | Scenario 01 fixed: capacity and load shedding |
| Lab: cache entries | `lab_cache_entries` | Scenario 05: broken grows forever, fixed stays at ≤ 500 |

If a panel is empty, check http://localhost:9190/targets. The `failure-lab` target must be **UP**, which
means the app is running.

## 2. Actuator: *ask the running app directly*

```bash
curl -s localhost:8180/actuator/health
curl -s localhost:8180/actuator/metrics                                   # every metric name
curl -s localhost:8180/actuator/metrics/hikaricp.connections.pending      # one metric, right now
curl -s localhost:8180/actuator/metrics/tomcat.threads.busy
curl -s localhost:8180/actuator/threaddump -H 'Accept: text/plain' > dumps/actuator-threads.txt
curl -s -o dumps/actuator.hprof localhost:8180/actuator/heapdump
```

⚠️ `/actuator/heapdump` and `/actuator/threaddump` expose memory contents and internals, including secrets.
**Never expose them publicly.** This lab exposes them only on your laptop.

## 3. Thread dumps: *what is every thread doing right now?*

```bash
make threaddump                           # = jcmd $PID Thread.print > dumps/threads-<ts>.txt
jstack $PID > dumps/threads.txt           # equivalent
```

**Reading thread states:**

| State | Meaning | Typical cause in this lab |
|---|---|---|
| `RUNNABLE` | On the CPU, or in native I/O | 06: `java.util.regex.Pattern$...` frames |
| `WAITING` / `TIMED_WAITING` | Parked, waiting for something | 01, 04: waiting on the HTTP response; 03: waiting for a Hikari connection |
| `BLOCKED` | Waiting for a `synchronized` monitor | lock contention (roadmap) |

**The request threads** are named `http-nio-8180-exec-N`. Useful recipes:

```bash
# how many request threads, in which state?
grep -A1 '"http-nio-8180-exec' dumps/threads-*.txt | grep 'State:' | sort | uniq -c

# which of OUR methods are they in?
grep -A30 '"http-nio-8180-exec' dumps/threads-*.txt | grep 'com.example.failurelab' | sort | uniq -c | sort -rn
```

**Take 3 dumps, 5 seconds apart.** One dump is a snapshot. If the *same* thread shows the *same* frames in every
dump, it's stuck, not busy. This is how you tell "slow" apart from "a lot of fast work".

## 4. Heap: *what is using the memory, and who is holding it?*

Cheap, and safe to run on a live system:
```bash
jcmd $PID GC.class_histogram | head -15   # instance count and bytes per class, top first
jcmd $PID GC.heap_info
```
Take the histogram twice. **The class whose count keeps growing** is your suspect.

A full heap dump (pauses the app while it's written, and the file is as big as the heap):
```bash
jcmd $PID GC.heap_dump dumps/manual.hprof
```
The lab also writes one automatically on `OutOfMemoryError` (`-XX:+HeapDumpOnOutOfMemoryError`, see `build.gradle.kts`).

**In Eclipse MAT** (File → Open Heap Dump):
1. **Leak Suspects report.** Generated automatically, and usually points straight at the problem.
2. **Dominator tree.** Objects sorted by *retained* size: the memory that would be freed if that object went away.
3. Right-click the suspect → **Path to GC Roots → exclude weak references.** This tells you *why* it can't be
   collected, for example a `static` field.

## 5. Java Flight Recorder: *where is the CPU and allocation going over time?*

```bash
jcmd $PID JFR.start duration=30s filename=dumps/rec.jfr   # low overhead, fine for production
```
Open `dumps/rec.jfr` in JDK Mission Control:
- **Method Profiling** (hot methods): CPU hotspots (scenario 06).
- **Memory → Allocations**: who allocates the most (scenario 05).
- **Lock Instances / Thread contention**: where threads block (roadmap scenarios).

## 6. Postgres: *what is the database actually doing?*

```bash
make psql                                 # direct connection, bypassing Toxiproxy
```

```sql
-- Who is connected, and what are they doing?  (scenario 03: 'idle in transaction')
select pid, state, now() - xact_start as xact_open_for, now() - query_start as query_running_for,
       left(query, 60) as query
from pg_stat_activity
where datname = 'failurelab' and backend_type = 'client backend';

-- Who is waiting on whom?  (lock problems)
select pid, wait_event_type, wait_event, pg_blocking_pids(pid) as blocked_by
from pg_stat_activity where wait_event is not null and datname = 'failurelab';

-- Plan and real timing of one query
explain analyze select * from book where author_id = 42;
```

| `state` | Meaning |
|---|---|
| `active` | Running a query right now |
| `idle` | Connected, no transaction: healthy pooled connection |
| `idle in transaction` | **A transaction is open but no query is running: the app is holding the connection while doing something else** |

To log every statement the database receives (scenario 02), and then turn it off again:
```bash
docker compose exec postgres psql -U lab failurelab \
  -c "alter system set log_min_duration_statement = 0" -c "select pg_reload_conf()"
docker compose logs -f postgres
docker compose exec postgres psql -U lab failurelab \
  -c "alter system reset log_min_duration_statement" -c "select pg_reload_conf()"
```

## 7. Toxiproxy: *break the network on purpose*

Proxies: `postgres` (app → :15432 → Postgres) and `downstream` (app → :18080 → WireMock).

| Command | Effect | Teaches |
|---|---|---|
| `./scripts/toxics.sh latency <proxy> <ms>` | Adds latency to the data (per chunk, so treat it as a minimum) | 02 (+5 ms on postgres), 04 (+30 s on downstream) |
| `./scripts/toxics.sh timeout <proxy>` | Black hole: the connection stays open, no data flows | The worst case: only a client-side timeout can save you |
| `./scripts/toxics.sh reset <proxy>` | TCP reset | Fast failure: retries and fallbacks |
| `./scripts/toxics.sh down <proxy>` | Connection refused | Fast failure: a "kind" outage |
| `./scripts/toxics.sh up <proxy>` | Re-enables the proxy after `down` | |
| `./scripts/toxics.sh list` | Shows proxies and active toxics | Checking what's currently broken |
| `./scripts/toxics.sh clear` (= `make toxic-reset`) | Removes everything | **Always run this after an experiment** |

## 8. k6: *reading a load-test summary*

`make load S=NN V=broken|fixed` prints a summary at the end. Look at:

- **`http_req_duration`**: `avg`, `p(90)`, `p(95)`, `max`. Compare **p95**, not the average.
- **`http_req_duration{name:canary}`**: the canary's latency on its own (scripts 01, 04, 06). The threshold
  `p(95)<250` shows ✗ when the whole app is starved.
- **`http_req_failed`**: the share of responses that were non-2xx/3xx, or failed to connect.
- **`checks`**: e.g. `served (200)` vs `shed fast (503)` in scenario 01. The split *is* the result.
- **`http_reqs`**: total count and requests per second (throughput).
- **`dial: i/o timeout`** warnings, about one per virtual user in the first second: Docker Desktop is forwarding a
  burst of new connections from the k6 container to the host. That's the load generator's environment, not the app. Always ask
  whether a failure is in the system under test or in the thing generating the load.
- **`iterations`** and `vus`: with `constant-vus`, a slow server means fewer iterations. That's coordinated
  omission (see the [failure taxonomy](failure-taxonomy.md)).

## 9. Logs and correlation ids: *from a 500 to its stack trace*

Every response carries `X-Correlation-Id`. A 500 body is a `ProblemDetail` containing the same id, but
**never** a stack trace:

```json
{"status":500,"title":"Internal Server Error",
 "detail":"Unexpected CannotCreateTransactionException; search the logs for the correlationId",
 "correlationId":"0ce8df60-7e9d-4c99-9d7d-88e17d17dbd2"}
```

Every log line written during that request includes the id (`logging.pattern.level: "%5p [%X{correlationId:-}]"`):

```bash
make run 2>&1 | tee dumps/app.log         # capture the log when you start the app
grep 0ce8df60-7e9d-4c99-9d7d-88e17d17dbd2 -A30 dumps/app.log
```

`X-Scenario` and `X-Variant` headers are added to every scenario response, which makes curl output and proxies
easier to read.
