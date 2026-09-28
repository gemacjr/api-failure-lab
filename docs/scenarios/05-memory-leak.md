# 05 — Memory leak → GC thrash → OutOfMemoryError

> **Category:** memory · **Endpoints:** `GET /scenarios/memory-leak/broken?key=…`, `GET /scenarios/memory-leak/fixed?key=…`, `DELETE /scenarios/memory-leak/broken/cache` · **Load script:** `k6/05-memory-leak.js`

## Symptom

> "The service gets slower the longer it runs. A restart fixes it, for a while."

In production it usually looks like one of these:
- latency that creeps up over hours or days, then drops back to normal after every deploy or restart;
- pods restarted with `OOMKilled`, or a `java.lang.OutOfMemoryError: Java heap space` in the logs;
- a memory graph shaped like a sawtooth whose **floor keeps rising**.

## Reproduce

```bash
make up
make run                     # note: bootRun uses -Xmx256m (see build.gradle.kts)

make load S=05 V=broken      # 20 req/s, every request a NEW key; watch Grafana's heap panel
```

After the `OutOfMemoryError`, a heap dump is waiting for you:

```bash
ls -lh dumps/*.hprof
```

Restart the app (`make run`) and compare against the bounded version:

```bash
make load S=05 V=fixed
```

To reset the leak without restarting: `curl -X DELETE localhost:8180/scenarios/memory-leak/broken/cache`.

## What you'll see

The load is 20 req/s with a unique key each time, and each entry is 100 KB, so about 2 MB/s is retained in the broken variant.

| Metric | broken | fixed |
|---|---|---|
| heap used after 1 min | 160 MB (1,154 entries) | 115 MB (500 entries) |
| heap used after 2 min | *no data*: the app stopped answering scrapes | 119 MB |
| heap used after 3 min | *no data* | 120 MB |
| time until `OutOfMemoryError` | **~1.5–2 min** (~2,200 entries ≈ 216 MB of `byte[]`) | never |
| requests OK over the 4-minute run | 2,209 / 3,702 (**40% failed**) | 4,800 / 4,800 |
| heap dump written | `dumps/java_pid<pid>.hprof`, 295 MB | — |

**After the OOM the JVM did not exit.** It stayed up as a zombie. `curl /api/ping` timed out, and the process had to
be killed. That's worse than a crash, because nothing restarts a process that is still "running".
In production, add **`-XX:+ExitOnOutOfMemoryError`** (or `-XX:+CrashOnOutOfMemoryError`) so the orchestrator
restarts it, keep `HeapDumpOnOutOfMemoryError` so you still get the evidence, and write the dump to a volume
that outlives the container.

## Diagnose

1. **Is memory really leaking, or just high?** Grafana → *JVM heap used vs max* (http://localhost:3300).
   Healthy memory is a sawtooth that returns to the **same floor** after each GC. A leak is a sawtooth whose
   **floor rises** after every collection, because the GC frees less each time: the objects are still reachable.
2. **Is it hurting yet?** *GC pause time per second* climbs as the heap fills. The GC runs more often and
   works harder to free less. This is **GC thrash**: the app hasn't crashed, but latency is terrible and CPU
   goes to the collector instead of requests. Many incidents are caught at this stage, not at the OOM.
3. **What is filling the heap?** Get a live class histogram. This is cheap and needs no heap dump:
   ```bash
   jcmd $(jps | awk '/FailureLabApplication/{print $1}') GC.class_histogram | head -12
   ```
   `[B` (byte arrays) sits at the top with hundreds of MB. Take it twice a minute apart: the count that keeps growing
   is your suspect.
4. **Who is holding it?** You need a heap dump for this. You get one automatically on OOM
   (`-XX:+HeapDumpOnOutOfMemoryError`), or on demand:
   ```bash
   jcmd <pid> GC.heap_dump dumps/manual.hprof
   # or: curl -sf -o dumps/actuator.hprof localhost:8180/actuator/heapdump
   ```
   Open it in [Eclipse MAT](https://eclipse.dev/mat/):
   - **Leak Suspects report**: MAT points at a single `ConcurrentHashMap` retaining most of the heap.
   - **Dominator tree**: `MemoryLeakController.LEAKY_CACHE` → `ConcurrentHashMap` → thousands of `byte[102400]`.
   - **Path to GC Roots** (on the map): the root is a *static field*, which answers *why it can never be collected*.
5. ⚠️ **Security:** a heap dump contains everything in memory, including passwords, tokens and personal data. `/actuator/heapdump`
   must never be exposed publicly. The lab enables it only because everything is bound to 127.0.0.1.

## Root cause

An **unbounded collection that is reachable from a GC root**. The garbage collector only frees objects
that nothing refers to anymore. `LEAKY_CACHE` is a `static` field, so it lives as long as the class,
which in practice means as long as the JVM. Every distinct `key` adds 100 KB, and nothing ever removes it:

```java
private static final Map<String, byte[]> LEAKY_CACHE = new ConcurrentHashMap<>();
...
LEAKY_CACHE.computeIfAbsent(key, MemoryLeakController::renderReport);   // only ever grows
```

Java "memory leaks" are almost never lost memory. They're memory you are **still referencing** without
meaning to. Common real-world versions:
- caches keyed by unbounded input: user id, URL, query string, search term;
- listeners or callbacks registered and never unregistered;
- `ThreadLocal`s set on pooled threads and never `remove()`d;
- metric tags with unbounded values (user id, full URL). Every distinct value creates a new time series in
  Micrometer. This is a **cardinality explosion**, and it leaks memory in both your app and your metrics backend.

## Fix

Give the cache an **eviction policy**, so the amount of memory it can hold has a ceiling:

```java
// broken: static, unbounded, never evicts
private static final Map<String, byte[]> LEAKY_CACHE = new ConcurrentHashMap<>();

// fixed: at most 500 entries (~50 MB here), and nothing lives longer than 5 minutes
private final Cache<String, byte[]> boundedCache = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(Duration.ofMinutes(5))
        .build();
```

Notes:
- `maximumSize` bounds *memory* only when entries are about the same size. When entry sizes vary a lot, use
  `maximumWeight` with a `weigher` that returns each entry's approximate byte size.
- Caffeine evicts asynchronously. The lab calls `cleanUp()` after every request only so the reported number is
  exact. You wouldn't do that in production.
- Keep `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=...` on in production. It costs nothing until the
  moment you need it, and without it the evidence disappears with the crashed process.

## Takeaway

**Every cache needs an eviction policy. A map that only grows is a leak on a timer.**

In code review, look for a `static Map`/`List`/`Set`, or a collection field on a singleton bean (every
`@Service` is one), that only ever has `put`/`add` called on it. Ask: "what removes entries from this, and what
bounds the number of distinct keys?"

## Further reading

- [Eclipse Memory Analyzer (MAT)](https://eclipse.dev/mat/): the Leak Suspects, Dominator Tree and GC-roots docs
- [Caffeine wiki: Eviction](https://github.com/ben-manes/caffeine/wiki/Eviction)
- [`jcmd` reference (JDK 17)](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jcmd.html)
- [Micrometer: tag cardinality](https://docs.micrometer.io/micrometer/reference/concepts/naming.html#_tag_values)
