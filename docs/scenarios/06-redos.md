# 06 — Catastrophic regex backtracking (ReDoS)

> **Category:** cpu · **Endpoints:** `GET /scenarios/redos/broken?input=…`, `GET /scenarios/redos/fixed?input=…` · **Load script:** `k6/06-redos.js`

## Symptom

> "CPU is at 100%, throughput is near zero, and there isn't a single error in the logs."

A handful of requests, not a flood, takes the service down. That's what makes this a *denial of
service*: an attacker (or an unlucky user) needs only a few carefully shaped inputs, and autoscaling on CPU
just adds more machines for the attacker to pin.

## Reproduce

```bash
make up && make run

# ONE request, 34 characters: 33 commas and an 'x'
time curl -s "localhost:8180/scenarios/redos/broken?input=$(printf ',%.0s' {1..33})x"
time curl -s "localhost:8180/scenarios/redos/fixed?input=$(printf ',%.0s' {1..33})x"

make load S=06 V=broken     # 16 attackers + 1 canary
make load S=06 V=fixed
```

Try growing the input by 2 characters at a time on `broken` and watch the time explode. The script uses 16 attackers
because that's more than most laptops' core count. Raise it if `sysctl -n hw.ncpu` (macOS) or `nproc` (Linux)
reports more.

## What you'll see

Time for a single request to `broken`, by input length. These timings are from the lab machine, JDK 17; the 31- and 41-char rows are from while the lab was being planned:

| Input (commas + `x`) | broken | fixed |
|---|---|---|
| 21 chars | ~60 ms | < 1 ms |
| 26 chars | ~120 ms | < 1 ms |
| 31 chars | ~0.9 s | < 1 ms |
| 34 chars (the attack payload) | (measured in Task 11) | < 1 ms |
| 41 chars | ~14 s | < 1 ms |
| 5,001 chars | (never finishes) | 1 ms, or `400` over 200 chars |

Under load (16 attackers + 1 canary, 45 s):

| Metric | broken | fixed |
|---|---|---|
| process CPU | (measured in Task 11) | (measured in Task 11) |
| attack requests completed | (measured in Task 11) | (measured in Task 11) |
| canary `/api/ping` p95 | (measured in Task 11) | (measured in Task 11) |

## Diagnose

1. **High CPU plus low throughput.** Grafana → *CPU usage (process vs system)* (http://localhost:3300) shows
   the process near 1.0 (100% of all cores), while *Request rate* is tiny. When the CPU is busy but the work
   isn't getting done, the CPU is being spent on something pathological.
2. **What's burning it?** Take **two** thread dumps about 5 seconds apart:
   ```bash
   make threaddump; sleep 5; make threaddump
   grep -A12 '"http-nio-8180-exec' dumps/threads-*.txt | grep -E 'State:|regex|RedosController' | sort | uniq -c
   ```
   The busy request threads are `RUNNABLE` in `java.util.regex.Pattern$Loop.match`,
   `Pattern$CharProperty.match` and similar frames, with `RedosController.broken` below them. **The same threads, in
   the same frames, in both dumps** tells you they're stuck in one long computation, not handling
   many short requests. This two-dump technique works for any CPU-bound hang.
3. **Optional, for a proper profile:** record with Java Flight Recorder and open the file in JDK Mission Control:
   ```bash
   jcmd $(jps | awk '/FailureLabApplication/{print $1}') JFR.start duration=30s filename=dumps/redos.jfr
   ```
   In *Method Profiling* (hot methods), `java.util.regex.Pattern$*` dominates.

## Root cause

Java's regex engine is a **backtracking** engine. When a match fails, it goes back and tries every other
way the pattern could have matched the input before giving up.

```
^(.*,){10}\d+$      "10 fields ending in a comma, then a number"
```

`.*` can also match commas. So for an input of 33 commas, the engine can split it into 10 `something,`
groups in an astronomical number of ways (roughly "33 choose 10"). The final `\d+` fails on the `x`, so the
engine tries **every one of those splits**. Each extra character multiplies the work.

**Why not the famous `(a+)+` example?** Since JDK 9, `java.util.regex` *memoizes* simple greedy loops,
so the classic textbook patterns (`^(a+)+$`, `^(\w+\s?)*$`, `^([a-zA-Z0-9]+)*@...`) run in about 0 ms on JDK 17.
We measured this while building the lab. But bounded repetition like `{10}` is **not** memoized. So "I tried
the textbook attack and it was fine" is **not** evidence that your regex is safe. The JDK protects you from some
patterns and not others, and the difference is invisible when you read the code.

Two properties make this especially nasty:
- **Matching cannot be interrupted.** A request timeout, or the client hanging up, does *not* stop the CPU
  burn. The thread keeps matching until it finishes.
- **It needs no volume.** Rate limiting per IP doesn't help much when 16 requests are enough.

## Fix

Remove the ambiguity, and bound the input **before** the regex ever sees it:

```java
// broken: `.*` can match commas -> astronomically many ways to split the input
Pattern.compile("^(.*,){10}\\d+$");

// fixed: `[^,]*` can NOT match a comma -> exactly one way to split -> linear time
Pattern.compile("^([^,]*,){10}\\d+$");
```
```java
// fixed endpoint: reject oversized input first (defence in depth)
if (input.length() > CsvRecordValidator.MAX_INPUT_LENGTH) {           // 200
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "input exceeds 200 characters");
}
```

**Bonus finding:** the broken pattern was also **functionally wrong**. `.*` accepts commas inside a field, so
it also accepted a *12*-field line. See the test `brokenPatternWasAlsoWrongItAcceptsTwelveFields`. An ambiguous
regex is often both slow and incorrect.

Better options, in order of preference:
1. **Don't use a regex for structured data.** `line.split(",", -1).length == 11` plus a number check is
   linear, readable and obviously correct.
2. **Write patterns that can only match one way:** use negated character classes (`[^,]*`), possessive quantifiers
   (`[^,]*+`) or atomic groups (`(?>...)`) so the engine can't backtrack into them.
3. **For patterns you don't control** (user-supplied search patterns), use a linear-time engine such as
   [RE2/J](https://github.com/google/re2j), which guarantees no catastrophic backtracking.
4. **Always** limit input length before matching.

## Takeaway

**Ambiguous repetition (`.*` inside a repeated group, `(x+)+`, overlapping alternatives) plus untrusted input
means a CPU denial of service.**

In code review, look for `.*` or `.+` inside a group that repeats (`(...)*`, `(...)+`, `(...){n}`), and
alternations whose branches can match the same text. Check that the input is length-limited before the match runs.

## Further reading

- [OWASP: Regular expression Denial of Service (ReDoS)](https://owasp.org/www-community/attacks/Regular_expression_Denial_of_Service_-_ReDoS)
- [Cloudflare outage of 2 July 2019](https://blog.cloudflare.com/details-of-the-cloudflare-outage-on-july-2-2019/): a real ReDoS that took down a large part of the internet
- Russ Cox, ["Regular Expression Matching Can Be Simple And Fast"](https://swtch.com/~rsc/regexp/regexp1.html)
- [RE2/J](https://github.com/google/re2j)
