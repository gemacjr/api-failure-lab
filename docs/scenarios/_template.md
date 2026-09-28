# NN — <Scenario title>

> **Category:** <threads | database | downstream | memory | cpu> · **Endpoints:** `GET /scenarios/<slug>/broken`, `GET /scenarios/<slug>/fixed` · **Load script:** `k6/NN-<slug>.js`

## Symptom
What a user or on-call engineer would notice first, in their words (e.g. "the whole API got slow, even health checks").

## Reproduce
Exact commands, copy-pasteable, starting from `make up` + `make run`.

## What you'll see
Measured numbers for broken vs fixed under the same load (a table): latency p95, error rate, the key resource metric.

## Diagnose
Step by step, in the order you would do it during a real incident: which Grafana panel, which metric query,
which dump or log line, and what in it proves the cause.

## Root cause
The mechanism, in plain words, and why this load triggers it.

## Fix
The broken code and the fixed code, side by side, with the one idea that makes the difference.

## Takeaway
One or two sentences worth remembering, plus how to spot this class of bug in code review.

## Further reading
Links.
