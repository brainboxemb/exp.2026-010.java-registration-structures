# Experiment 010 — Java registration structures

Standalone Java 8-compatible experimental implementation for Event Timing registration-state architecture.

## Boundary

This experiment does **not** modify production. Its reference is
`brainboxemb/2026-010-02.java.timing-point-application`.
The Raspberry Pi image is owned by a separate repository; this repo owns the
workloads, correctness evidence, benchmarks and eventual target-hardware verification.

## Foundation (first experimental slice)

Two interchangeable, intentionally simple ranking representations:

- `REBUILD`: append to a primitive elapsed-time array; sort a reusable copy on the first rank query after a mutation.
- `SORTED_ARRAY`: maintain a sorted primitive array incrementally on each rankable ADD/REV/start change.

Both retain chronological ADD rows including revoked entries, resolve REV newest-first by (registration ID, UTC passage milliseconds), apply per-registration stage starts, retain competition ranking with ties (1, 1, 3), and expose bounded sequence-based display visits. Row and rank-array growth events and separate state/ranking revision counters are observable.

This first slice uses **absolute UTC epoch milliseconds** for starts and passages.
It intentionally does not replicate production's `StageTiming` logic for compact time-only timestamps, midnight or DST. These require a separate correctness test suite before interpreting end-to-end timings.

## Tests

```shell
mvn -B verify
```

JUnit 5 exercises ties, multiple identical passages, newest-first REV, missing/changed stage starts, and seeded differential workloads across initial array capacity.

## Measurement roadmap

1. Validate against an independent oracle (not merely cross-candidate agreement).
2. Add retained-state and query/update change-propagation correctness tests including page cursors.
3. Add explicit baseline (`ArrayList` + full ranking recalculation) to match the actual current production behaviour; do not infer end-to-end performance from isolated ranking.
4. Measure fresh JVM startup separately from warm construction/preallocation for 500 rows, then 512→513 growth, 1,000 and 5,000.
5. Separate steady-state allocations per operation, rank/query latency and changed-display projection cost. JMH or equivalent controlled repetitions are required; simple wall-clock runs are diagnostic only.
6. Rerun the selected candidates on the real Pi Zero with the separately built OS/runtime image.

No performance superiority is claimed at this stage.
