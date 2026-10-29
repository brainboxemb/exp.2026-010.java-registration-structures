# RegistrationList diagnostic — 2026-10-29

## Provenance

- Measured commit: `5aadba46e1cb550330fa333dc6ab17a75ead952f`
- GitHub Actions run: https://github.com/brainboxemb/exp.2026-010.java-registration-structures/actions/runs/40833288897
- Result: success
- GitHub-hosted Ubuntu, OpenJDK 64-Bit Server VM / Java 17
- Source target: Java 8
- 3 warmups, 5 measured samples, median
- Diagnostic harness only: no JMH forks, confidence intervals, CPU-frequency control or Pi Zero yet

This run measures the complete experimental `RegistrationList` around each interchangeable
`RankingIndex`, not only the ranking structure.

The list uses primitive append-only row arrays, a primitive `RegistrationIndex`,
a bounded primitive mutation ring, cached row start/elapsed times and visitor-based reads.

## Normal population: 500 retained registrations

Median nanoseconds per operation:

| Scenario | Rebuild+sort | Sorted array | TreeMap | Pooled AVL |
| --- | ---: | ---: | ---: | ---: |
| Initial fill | 494.1 | 428.9 | 449.5 | 272.8 |
| Direct REV (`validCount == 1`) | 268.3 | 458.9 | 656.0 | 89.0 |
| Exceptional REV scan (`validCount == 2`) | 1,365.2 | 643.2 | 373.1 | 347.5 |
| Start-time change (scan list + update ranking) | 847.9 | 7,558.7 | 1,133.7 | 752.5 |
| Snapshot row (including current rank) | 171.8 | 188.3 | 2,675.7 | 69.0 |
| Mutation-ring entry read | 87.3 | 79.5 | 86.1 | 86.2 |

These values are one CI run and must not be presented as stable production latencies.
The non-monotonic 1,000/5,000 results elsewhere in the CSV show that JIT/runner effects
are material.

## What this run does support

### 1. Keep the exceptional linear scan simple

The multiple-registration REV workload deliberately constructs two valid rows for each id,
with the row being revoked in the old half of the list. REV therefore scans backwards to find
the matching row, then scans again after `2 -> 1` to recover `onlyValidRow`.

At 500 retained rows that complete exceptional path remained in the sub-microsecond to low
microsecond range on this runner.

That supports the current design choice:

- store only `validCount + onlyValidRow` in the primitive RegistrationIndex;
- do **not** maintain a per-id dynamic list of every row position;
- measure again on Pi Zero before reconsidering.

### 2. Stable row positions make REV/synchronization cheap

REV never removes or shifts a row. A row index remains stable for the lifetime of the
RegistrationList and can therefore be used as the subject of ROW_ADDED/ROW_REVOKED mutations.

The normal one-valid-row REV path uses the RegistrationIndex directly and performs no row scan.

### 3. The mutation ring is small and ranking-independent

Reading a 64-entry mutation batch was roughly 80–90 ns per entry for all four ranking
implementations and showed zero measured thread allocation.

The mutation ring contains only kind + subject + flags. Registration data is read from the
current RegistrationList instead of copied into every mutation.

### 4. Custom steady-state paths avoid object churn

Measured thread allocation per operation in these steady workloads:

| Workload @ 500 | Rebuild+sort | Sorted array | TreeMap | Pooled AVL |
| --- | ---: | ---: | ---: | ---: |
| Direct REV | 0 B | 0 B | 48 B | 0 B |
| Exceptional REV | 0 B | 0 B | 48 B | 0 B |
| Start change | 0 B | 0 B | ~72 B | 0 B |
| Snapshot row | ~9 B* | 0 B | 168 B | 0 B |
| Mutation read | 0 B | 0 B | 0 B | 0 B |

`*` Rebuild+sort's snapshot includes first-use preparation/sorting after the filled list was
constructed; allocation attribution should be separated more rigorously in JMH/profiling.

Initial-fill allocation includes construction of all preallocated arrays, so its per-operation
number is not steady-state churn.

### 5. Start time cached in each row is affordable

Each RegistrationList row now stores:

```text
long sequence
int  registrationId
long passageTime
long startTime
long elapsedTime
byte flags
```

The extra cached `startTime[]` is one long per retained row: about 4 kB for 500 rows
(before JVM array headers/alignment). It removes the need for a RegistrationIndex lookup on
every visitor/snapshot row.

RegistrationIndex still retains the current start time per registrationId so a later ADD can
copy the correct start directly into its new row.

## Scaling checks

The diagnostic also ran 1,000 and 5,000 rows. Important qualitative observations:

- exceptional scans remain small in absolute terms;
- start-change cost includes both the O(N) RegistrationList scan and the chosen ranking
  structure's remove/add cost;
- TreeMap continues to allocate during ranking mutation/query operations;
- primitive mutation-ring reads remain allocation-free;
- snapshot cost strongly depends on the RankingIndex because rank is intentionally calculated
  at read time.

Absolute scaling figures should be taken from the retained CSV artifact, not copied into an
architecture claim from this single run.

## Next measurements

1. Add JMH/forked versions of the 500-row direct REV, exceptional REV, start change and snapshot.
2. Separate pure list-scan time from RankingIndex mutation time.
3. Measure direct row sync after ROW_ADDED/ROW_REVOKED and per-registration sync after
   START_CHANGED.
4. Add a realistic mutation ratio rather than only synthetic isolated scenarios.
5. Repeat on Pi Zero.
