# Experiment 010 — Java registration structures

**New to this repository? Start with [ARCHITECTURE.md](ARCHITECTURE.md).**
It explains the experiment step by step using a four-record LogBook example,
then tells you which Java file to read first. `DiagnosticBenchmark` is only
a measurement tool; it is not the application entry point.

Standalone Java 8-compatible experiment for Event Timing registration-state architecture.

The experiment does **not** modify production. The production reference is
`brainboxemb/2026-010-02.java.timing-point-application`; the Raspberry Pi
image project separately owns the OS image.

## Four compared representations

| Model | Retained registration state | Ranking update policy | Display paging |
| --- | --- | --- | --- |
| `PRODUCTION_EAGER` | `ArrayList` of row objects | Whole-list rank recalculation after each accepted live ADD/REV and affected stage-start change | Chronological scan |
| `REBUILD` | Object-row array | Rebuild primitive sorted scratch on first rank query after changes | Binary-search cursor |
| `SORTED_ARRAY` | Object-row array | Incremental sorted `long[]` index | Binary-search cursor |
| `PACKED_SORTED` | Parallel primitive row arrays (no per-ADD Row object) | Incremental sorted `long[]` index | Binary-search cursor |

All models retain invalidated ADDs, revoke newest-valid matching ADDs by
(registration ID, UTC passage milliseconds), support per-registration stage starts,
competition ranks with ties (1, 1, 3), bounded display visits, and a preallocated
bounded change journal with explicit expired-cursor handling.

**Important boundary:** The production-like model reproduces the **eager ranking
algorithm**, not all Event Timing implementation details, message plumbing or
`StageTiming` UTC/time-only ambiguity handling. Its `ArrayList` capacity/growth
counters are estimates; timing and allocation bytes are measured in the JVM.
The trial uses absolute UTC epoch milliseconds for both starts and passages.

## Run locally

```sh
mvn -B verify
java -cp target/classes emb.experiment.registration.DiagnosticBenchmark 500
```

Tests include an **independent counting oracle** across all four models,
deterministic ADD/REV/start workloads, display paging, journal overflow,
512→513 growth and a 5,000-row case.

See [BENCHMARK.md](BENCHMARK.md) for diagnostic measurement definitions,
limitations and next checks. No production architecture recommendation has
been established yet.
