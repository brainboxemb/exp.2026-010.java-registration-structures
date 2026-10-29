# Current architecture decision

Date: 2026-10-29

Status: **preferred experiment candidate; production migration still requires Pi Zero and
production StageTiming validation**

## Decision

Use the following as the preferred Experiment 010 architecture:

```text
RegistrationList
    primitive append-only row arrays
    1024 retained-row capacity

RegistrationIndex
    primitive open-addressed table
    1024 slots
    validCount + onlyValidRow + current start

RankingIndex
    SortedArrayRanking
    primitive sorted long[]
    ~512 active/rankable capacity

RegistrationMutations
    primitive bounded ring
    1024 live mutation slots
```

Historical LogBook replay is silent. The first display snapshot has mutation revision 0.
Only subsequent live changes enter RegistrationMutations.

Rank is calculated at read time and is never stored in registration rows.

## Why SortedArrayRanking

At the normal ~500-entry design point the sorted primitive array gives the best overall
engineering trade-off.

### JMH

Forked JMH evidence:

https://github.com/brainboxemb/exp.2026-010.java-registration-structures/actions/runs/40850108543

| Operation | Sorted array | Pooled AVL |
| --- | ---: | ---: |
| Pure rank lookup | 12.031 ns | **8.281 ns** |
| Replace/prepare/rank/restore | **97.012 ns** | 143.250 ns |
| Direct row sync | 12.199 ns | **9.295 ns** |
| Per-registration sync scan | 348.759 ns | 343.784 ns |
| 500-row snapshot | 5.867 µs | **5.371 µs** |
| Start change + sync roundtrip | 1.684 µs | **1.666 µs** |

Both custom candidates have effectively zero steady-state allocation in these JMH paths.

The AVL is faster at pure rank lookup and about 0.5 µs faster for a complete 500-row
snapshot. The sorted array is materially faster for ranking mutations, while the complete
start-change path is effectively tied.

### Retained object graph

Memory probe:

https://github.com/brainboxemb/exp.2026-010.java-registration-structures/actions/runs/40852964909

| Scope | Sorted array | Pooled AVL |
| --- | ---: | ---: |
| RankingIndex only | **4.05 KiB** | 26.07 KiB |
| Complete 500-row RegistrationList graph | **71.44 KiB** | 93.45 KiB |

JOL self-attach was enabled successfully; the GitHub runner still disallows its
Serviceability Agent. The retained object-graph measurement remained stable across both runs. The relative result is nevertheless consistent
with the representations: SortedArray owns one primitive array while PooledAVL preallocates
512 Node objects plus its node-reference array.

## Why not PooledAvlRanking

The AVL remains technically good, but its extra complexity is not justified by the measured
benefit at ~500 entries.

It requires:
- custom balancing/rotations;
- subtree occurrence bookkeeping;
- a node pool;
- 512 Node objects at normal preallocation;
- substantially larger retained heap.

In exchange it gives only a few nanoseconds on individual reads and about half a microsecond
on a complete 500-row snapshot on the desktop runner.

Keep it in the experiment as the O(log N) scaling/reference implementation.

## Why not TreeMapRanking

TreeMap is not a serious production candidate for this use case.

Forked JMH showed:
- ~528 ns rank lookup;
- ~424 µs for a 500-row snapshot;
- ~64 kB allocation per snapshot;
- ~128 B allocation for a single rank/direct-row read.

A normal TreeMap has no subtree occurrence counts, so competition rank requires walking the
head map. Boxing/tree-node allocation also conflicts with the low-churn goal.

Keep it only as a standard-library reference.

## Why not RebuildSortRanking

Rebuild+sort remains the baseline representing the current cost pattern.

It performs well for reads after it is already prepared, but a ranking mutation that requires
another prepare/sort is much more expensive:

- 16.67 µs JMH replace/prepare/rank/restore versus 97 ns for SortedArray.

It is therefore not the preferred live ranking structure.

## Why keep the simple RegistrationList scans

The surrounding list design deliberately does not maintain a dynamic list of row positions
for each registrationId.

Evidence:
- ~500-row exceptional REV scan is around the low-microsecond range in diagnostics;
- JMH per-registration 500-row sync scan is ~0.35 µs with the custom ranking candidates;
- no steady-state allocation is needed.

This is cheaper in engineering complexity and memory than maintaining another variable-size
index for an exceptional case.

## Preallocation decision

Use separate capacities for separate concepts:

```text
retained ADD rows     1024
unique/id index       1024
active ranking values ~512
live mutation history 1024
```

The retained row list needs more headroom than the active ranking index because REV preserves
ADD rows. A timing point with ~500 active registrations can therefore exceed 512 retained rows
without having more than ~500 active/rankable registrations.

The mixed diagnostic workload confirmed that preallocating the row capacity avoids the
512→1024 whole-array copy and gives zero measured steady-state allocation for SortedArray.

## Remaining gates before production

This decision is strong enough to stop inventing additional desktop data structures, but not
yet to change production.

Remaining gates:

1. run the selected structures on the Pi Zero;
2. validate compact/time-only StageTiming and midnight/DST semantics against production;
3. map real LogBook ADD/REV/location filtering and OPEN replay semantics;
4. verify the actual display protocol/snapshot payload around the mutation cursor;
5. then implement the production refactor with regression fixtures.

If Pi Zero measurements do not show a material contrary result, SortedArrayRanking should be
the production RankingIndex implementation.
