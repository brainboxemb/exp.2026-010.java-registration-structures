# RegistrationList diagnostic — 2026-10-29

## Provenance

- Measured commit: `99632df0062b0392ce461f59c4db0043b86291a3`
- GitHub Actions run: https://github.com/brainboxemb/exp.2026-010.java-registration-structures/actions/runs/40849728457
- Result: success
- GitHub-hosted Ubuntu, OpenJDK 64-Bit Server VM / Java 17
- Source target: Java 8
- 3 warmups, 5 measured samples, median
- Diagnostic harness only: no JMH forks, confidence intervals, CPU-frequency control or Pi Zero yet

This run measures the complete experimental `RegistrationList` around each interchangeable
`RankingIndex`.

The measured implementation uses:

- primitive append-only registration row arrays;
- primitive open-addressed `RegistrationIndex`;
- cached row start + elapsed time;
- bounded primitive `RegistrationMutations` ring;
- silent historical `beginInitialLoad()/finishInitialLoad()`;
- visitor-based row/snapshot reads without temporary result lists.

## Pure surrounding-structure cost

`LIST_ONLY` uses a benchmark-only O(1) ranking stub so the list/index/ring costs are visible
without a ranking algorithm dominating them.

At 500 retained rows:

| Scenario | Median |
| --- | ---: |
| Direct REV, one valid row | 250.5 ns |
| Exceptional REV scan, two valid rows/id | 1,042.2 ns |
| Start-time scan/update | 863.8 ns |
| Snapshot visit | 31.6 ns / row |
| Mutation-ring visit | 95.0 ns / mutation |

All five reported **0 measured allocated bytes/op**.

The exact nanosecond figures vary significantly between GitHub-hosted runs. The useful
conclusion is structural: the exceptional full-list scan remains very small at ~500 rows and
does not justify a second variable-size per-id positions collection at this stage.

## Mixed workload

The deterministic 1,024-operation profile starts from a silent 500-row initial load. Each
16-operation block performs:

- 4 duplicate ADDs;
- 4 matching REVs;
- 4 start-time changes;
- 3 mutation-sync batches;
- 1 full snapshot.

Valid registration count stays approximately stable while retained ADD rows grow from 500 to
756. This intentionally represents the fact that REV does not delete retained rows.

### 512-row start versus 1024-row preallocation

| RankingIndex | Cross 512→1024 | Preallocated 1024 rows | Alloc/op after preallocation |
| --- | ---: | ---: | ---: |
| Rebuild + sort | 2.878 µs/op | 2.504 µs/op | 0 B |
| Sorted primitive array | 1.314 µs/op | 1.073 µs/op | 0 B |
| TreeMap | 18.997 µs/op | 17.511 µs/op | 3286.7 B |
| Pooled order-statistic AVL | 1.143 µs/op | 1.008 µs/op | 0 B |

The preallocated scenario uses **1024 RegistrationList rows but only 512 RankingIndex
capacity**. Row-retention capacity and active-ranking capacity are intentionally independent.

The 512→1024 scenarios report about 37 B/op for the custom non-TreeMap candidates. This is the
one-time primitive-array growth/copy amortized over the 1,024 operations; it is not normal
per-mutation object churn.

### Interpretation

This run supports a candidate normal configuration of:

```text
RegistrationList rows:       1024
RegistrationIndex slots:     1024
RankingIndex active entries: ~512
RegistrationMutations:       1024
```

The extra row headroom is useful because a system with around 500 valid/unique registrations
can retain more than 500 ADD rows after duplicates and REV. Reserving only 512 rows therefore
makes an early whole-array growth likely.

For the primitive sorted ranking candidate, moving from 512 to 1024 row preallocation costs
about 18.5 KiB of extra primitive row payload while avoiding that likely copy.

## Silent initial load

Historical LogBook replay now builds RegistrationList, RegistrationIndex and RankingIndex
without writing display mutations.

Conceptually:

```text
beginInitialLoad()
    replay ADD / REV / starts
finishInitialLoad()

first snapshot -> mutationRevision 0

live ADD -> revision 1
live REV -> revision 2
...
```

This has two benefits:

1. the mutation ring is reserved for live display synchronization rather than historical
   reconstruction;
2. a snapshot marker has a clean meaning from the start.

Dedicated tests verify that visitors are unavailable while initial load is incomplete and
that the first live change after the initial snapshot gets revision 1.

## Correctness evidence

In addition to targeted RegistrationList tests, the branch now contains an independent
object/list oracle that drives seeded mixed ADD/REV/start/delete operations through every
RankingIndex implementation.

The oracle independently verifies:

- every retained row;
- cached start/elapsed time;
- valid state;
- current competition rank;
- retained/valid counts per registrationId;
- unique retained/valid registration counts;
- LogBook sequence progress;
- display mutation revision;
- per-registration visitor results.

CI is green for this oracle.

## Memory shape

Current candidate primitive payload:

| Structure | Capacity | Primitive payload |
| --- | ---: | ---: |
| RegistrationList row arrays | 1024 | 37,888 B |
| RegistrationIndex arrays | 1024 | 24,576 B |
| RegistrationMutations arrays | 1024 | 6,144 B |
| Sorted RankingIndex array | 512 | 4,096 B |
| **Total** |  | **72,704 B (~71 KiB)** |

JVM array/object headers and alignment are excluded.

The TreeMap and pooled-AVL representations have different object overhead and need retained
heap profiling for a fair full-memory comparison.

## Current evidence-based direction

Keep the simple surrounding design:

- append-only primitive RegistrationList;
- stable row positions;
- direct `onlyValidRow` common REV path;
- linear scan for exceptional multiple registrations and start changes;
- cached start + elapsed values in each row;
- rank calculated at read time from a separate RankingIndex;
- bounded primitive mutation ring;
- silent initial replay followed by snapshot revision 0;
- 1024 retained-row preallocation as the current candidate.

For RankingIndex, the sorted primitive array and pooled AVL remain the strongest custom
candidates in the mixed workload. The sorted primitive array remains notably simpler; the AVL
is useful as the O(log N) scaling comparison.

## Remaining evidence

1. Forked/JMH measurements for the 500-row steady-state paths.
2. Retained-heap measurement, especially TreeMap versus pooled AVL.
3. More realistic event ratios derived from production/old-software traces if available.
4. Pi Zero qualification using the separate generic Raspberry Pi image.
5. Production StageTiming/compact-time parity before migration.
