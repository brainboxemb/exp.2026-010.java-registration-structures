# Ranking diagnostic — 2026-10-29

## Provenance

- Commit: `6c5abd122de6dc01c1f1bff109bb0c5042003742`
- GitHub Actions run: https://github.com/brainboxemb/exp.2026-010.java-registration-structures/actions/runs/40828026255
- Result: success
- CI JVM: OpenJDK 64-Bit Server VM, Java 17
- Source target: Java 8
- 3 warmups, 5 measured samples, median reported
- Same deterministic workload for every solution

These are **diagnostic** results, not final benchmark evidence. Runner load, JIT,
GC and CPU-frequency effects are not controlled as they would be in JMH/forked
measurements.

## Normal population: 500 elapsed times

Nanoseconds per operation:

| Scenario | Rebuild + sort | Sorted primitive array | TreeMap | Pooled AVL |
| --- | ---: | ---: | ---: | ---: |
| Initial fill | 377.6 | 72.3 | 379.2 | 157.2 |
| Replace elapsed + prepare rank | 10,577.5 | **124.6** | 509.4 | 448.4 |
| Replace + prepare + rank lookup | 10,496.3 | **211.4** | 2,540.4 | 458.5 |
| Rank lookup only | **38.1** | 74.0 | 521.5 | 41.9 |

The normal 500-entry diagnostic strongly favors the **sorted primitive array**
for mutation-heavy ranking maintenance. The pooled AVL has similarly cheap
rank queries, but its tree maintenance is more expensive than shifting this
small array on this runner.

Rebuild+sort has a cheap lookup once prepared, but preparing after each changed
elapsed time dominates its cost. Standard `TreeMap<Long,Integer>` is convenient
but rank lookup is expensive because a normal TreeMap does not store subtree
occurrence counts.

## Allocation during steady replacement workload

Allocated thread bytes per replacement:

| Population | Rebuild + sort | Sorted array | TreeMap | Pooled AVL |
| --- | ---: | ---: | ---: | ---: |
| 500 | 0.0 | 0.0 | 134.9 | 0.0 |
| 1,000 | 0.0 | 0.0 | 135.3 | 28.0* |
| 5,000 | 15.5* | 0.0 | 135.8 | 0.0 |

`*` The deterministic workload crosses an internal capacity boundary in that
sample. This is exactly why capacity-growth cost is measured separately rather
than treating every allocation as steady-state churn.

The TreeMap candidate continually boxes keys/counts and creates/removes tree
nodes. The custom primitive array and pooled AVL are able to run the normal
500-entry mutation workload without measured thread allocation after setup.

## Capacity boundary

The explicit 512 -> 513 diagnostic produced one growth event for the custom
array candidates and for the pooled AVL when 513 distinct AVL nodes were needed.
The exact single-operation latency is too noisy to use as an architecture
decision; the important observation is that growth is visible and not a hard
500/512 entry limit.

## Scale check

For `replace + prepare + rank`:

| Population | Rebuild + sort | Sorted array | TreeMap | Pooled AVL |
| ---: | ---: | ---: | ---: | ---: |
| 500 | 10.50 µs | **0.21 µs** | 2.54 µs | 0.46 µs |
| 1,000 | 23.94 µs | **0.08 µs** | 3.56 µs | 0.27 µs |
| 5,000 | 135.82 µs | **0.16 µs** | 11.09 µs | 0.28 µs |

Absolute values vary significantly with JIT warmup and runner conditions; the
non-monotonic numbers are a reminder not to present these as stable production
latencies. The broad relative pattern is the evidence worth carrying forward
to a stronger benchmark.

## Current conclusion

For the expected ~500 registration population, the experiment should continue
with at least:

1. **Sorted primitive array** — simplest and currently fastest mutation candidate.
2. **Pooled order-statistic AVL** — more complex, but gives O(log N) mutation/rank
   behaviour and remains a useful scaling/zero-churn comparison.
3. **Rebuild+sort** — retain as the current cost-pattern baseline.

`TreeMap` can remain as a standard-JDK comparison, but these diagnostics give
no reason to prefer it for this workload.

Before any production change: repeat in JMH/forked runs, characterize realistic
race-day mutation/query ratios, then run the same comparison on the Pi Zero.
