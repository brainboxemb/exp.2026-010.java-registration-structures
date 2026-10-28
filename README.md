# Experiment 010 — Java registration structures

Standalone Java 8-compatible experiment for Event Timing registration-state architecture.

The experiment does **not** modify production. The production reference is
`brainboxemb/2026-010-02.java.timing-point-application`. The separate Raspberry Pi
image project owns the OS build; this repository owns experimental workloads, tests,
performance diagnostics and eventual target evidence.

## Current implementation

Two interchangeable ranking structures:

- `REBUILD`: retain primitive elapsed-time values and rebuild a sorted copy on
  first rank query after a mutation.
- `SORTED_ARRAY`: keep a primitive sorted elapsed-time array up to date.

Both models retain chronological ADD rows, resolve REV newest-first using exact
(registration ID, UTC passage time), apply stage starts, calculate competition ranks
with ties (1, 1, 3), and provide bounded sequence-based display visits.

The first version deliberately uses absolute UTC epoch milliseconds for starts and
passages; it does not recreate production's compact/time-only stage start rules.

## Run

```sh
mvn -B verify
java -cp target/classes emb.experiment.registration.DiagnosticBenchmark 500
```

Tests include an **independent counting oracle**, deterministic ADD/REV/start workloads,
display paging, capacity growth over 512 rows and a 5,000-row case.

See [BENCHMARK.md](BENCHMARK.md) for the workload definitions, fresh-JVM probe,
allocation/growth measurement, limitations and unfinished architecture comparisons.
The benchmark is diagnostic only; there are no validated performance conclusions.
