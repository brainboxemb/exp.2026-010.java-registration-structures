# Desktop diagnostic evidence — 2026-10-29

## Provenance

- Repository: `brainboxemb/exp.2026-010.java-registration-structures`.
- Branch: `experiment/registration-projection-foundation`.
- Exact measured commit: `ce040c6a63c087fc1caa6c5aa2be6288459c2bfe`.
- [GitHub Actions diagnostic run #40815194353](https://github.com/brainboxemb/exp.2026-010.java-registration-structures/actions/runs/40815194353) — **success**.
- Runner: GitHub-hosted Ubuntu; OpenJDK 64-Bit Server VM, Java 17 (check run logs for patch version).
- Model code targets Java 8; this CI diagnostic ran on Java 17.
- Deterministic 2,048-operation event stream, 3 warmups and 5 timed samples; figures are medians.
- Display page: at most 32 chronological rows after an initial-population midpoint sequence.
- Each column below is **microseconds per mutation**, inclusive of queries when scheduled.
- Values are for **one CI run**; no confidence intervals, randomized ordering, warmup-isolation
  study, JMH forks, or Pi Zero verification.

## Timing observations

| Population | Scenario | PRODUCTION_EAGER | REBUILD | SORTED_ARRAY | PACKED_SORTED |
| --- | --- | ---: | ---: | ---: | ---: |
| 500 | Mutation only | 48.209 | 0.858 | 0.922 | 1.242 |
| 500 | Page 32 / 16 mutations | 48.041 | 2.632 | 1.293 | 1.046 |
| 500 | Page 32 / mutation | 47.611 | 12.451 | 1.112 | 1.133 |
| 1,000 | Mutation only | 81.825 | 0.708 | 0.889 | 0.762 |
| 1,000 | Page 32 / 16 mutations | 81.466 | 2.667 | 0.964 | 0.818 |
| 1,000 | Page 32 / mutation | 82.398 | 19.976 | 1.434 | 1.182 |
| 5,000 | Mutation only | 338.769 | 2.755 | 4.863 | 4.329 |
| 5,000 | Page 32 / 16 mutations | 338.256 | 10.318 | 4.997 | 4.419 |
| 5,000 | Page 32 / mutation | 343.504 | 81.602 | 5.557 | 5.010 |

**These results do not establish a production winner.** They indicate that eager
full-projection ranking dominates live mutation cost when most rows are rankable.
Deferring sorting helps mutation-only work, while maintaining a sorted index becomes
more attractive when display rank queries are frequent. Both tradeoffs depend on
actual race/event workloads and target hardware.

## Allocation observation (mixed workload)

Thread-allocated bytes per mutation, in the **mutation only** workload:

| Population | PRODUCTION_EAGER | REBUILD | SORTED_ARRAY | PACKED_SORTED |
| --- | ---: | ---: | ---: | ---: |
| 500 | 58.78 | 44.57 | 44.57 | 52.13 |
| 1,000 | 67.02 | 46.56 | 46.56 | 91.63 |
| 5,000 | 51.64 | 34.55 | 34.55 | 4.55 |

The packed model allocates no row object per ADD, but **not zero bytes per
operation**: boxing of map data, array capacity growth and other costs remain.
A workload crossing a capacity boundary may have higher average allocation than
an object-row alternative. Population and mutation mix also change absolute
costs. Retained memory must be measured separately from allocated bytes.

## Correctness gate

`mvn -B verify` passed for the measured SHA, including the independent O(N²)
oracle comparing all four models, tie-ranking, latest matching REV, stage starts,
5,000 retained rows, cursor paging and change-journal overflow. This means model
semantics agree with the experiment's oracle; **protocol/production equivalence
is not yet established**. The baseline reproduces the production-like eager
ranking approach under normalized UTC epoch-millisecond timestamps, not all
application-domain behaviour.

## Follow-up

- Repeat benchmarks in forked JMH runs and vary read/update ratios and start change
  frequency (including an actively changing stage start affecting many rows).
- Capture retained heap usage, pool and capacity growth separately, not only
  thread-allocated bytes and whole-JVM RSS.
- Add production event/compact timestamp parity fixtures and a race-day realistic
  distribution of registrations.
- Qualify selected candidates on a Pi Zero with the separately built generic image.
