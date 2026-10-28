# Experiment 010 — measuring registration-state alternatives

This experiment is intentionally separate from the production Event Timing application and
from the Raspberry Pi image build. It supplies **correctness evidence before performance claims**.

## What the implementation actually models

The trial stores all ADD rows in original sequence order. An accepted REV invalidates the
newest matching valid row by (registration ID, exact passage UTC millisecond), preserving
the row for display. Per-ID stage starts can be set or deleted; a row is rankable when
it is valid and its passage is on/after its start. Competition rank is one plus the count
of strictly faster valid rows, including duplicate times. A display visit returns at
most the requested limit after an original ADD sequence cursor. The 256-entry
preallocated change journal reports changed rows, start updates and conservative
global rank invalidations. An expired change cursor fails explicitly, so a
display can detect when a complete resynchronization is required.

The two candidate ranking structures share the same retained-row implementation:
- **REBUILD**: unsorted primitive elapsed-time values; a reusable sorted copy is rebuilt
  at the first ranking query after rankable mutations.
- **SORTED_ARRAY**: primitive elapsed-time array kept sorted on every rankable mutation,
  using binary-search insertion and array shifting.

This isolates ranking-index trade-offs, **not** Java object-vs-primitive row storage.
The current experimental row representation has **one allocated Row object per ADD**.
The reference application likewise retains chronological ADD rows, but ranks its whole
projection after live mutations. A faithful full-recalculation production baseline is
**not yet included**. Do not interpret REBUILD as that baseline.

Stage-starts and passages in this first slice are absolute UTC epoch milliseconds.
Production additionally supports compact UTC and time-only starts, with strict ambiguity
handling at midnight and DST boundaries. These semantics must be covered by a later
parity suite before considering production integration.

## Repeatable correctness validation

```sh
mvn -B verify
```

The independent oracle never uses either ranking candidate's index and computes the
competition rank by counting every strictly faster valid row. The test suite covers:
duplicate elapsed times; repeated identical ADDs and newest-first REV; start SET/DEL;
no-op updates; unmatched REV; bounded sequence paging; seeded mixed operations; 512→513
row growth; and 5,000 retained rows. The same tests run in GitHub Actions.

Passing these checks establishes only the implemented model, **not** parity with every
production domain rule.

## Warm-JVM diagnostic harness

```sh
mvn -B verify
java -cp target/classes emb.experiment.registration.DiagnosticBenchmark
java -cp target/classes emb.experiment.registration.DiagnosticBenchmark 500
```

The default populations are 500, 1,000 and 5,000 entries. Each ranking mode reports:

| Scenario | What is measured |
| --- | --- |
| `construct_512` | Creating 100 initially empty projections with capacity 512 |
| `initial_fill` | Constructor, 128 stage starts and first N ADD rows |
| `grow_512_to_513` | A single additional ADD crossing a capacity boundary |
| `mutation_only` | 2,048 deterministic ADD/REV/SET/DEL operations, no reads |
| `page32_every_16_mutations` | The same operations with a 32-row paged read every 16 mutations |
| `page32_every_mutation` | The same operations with a 32-row paged read after every mutation |

The harness prints the median of five samples after three warmups. It reports
nanoseconds/operation, allocated thread bytes/operation when supported by the JVM,
growth events and a checksum. Workload generation is outside timed sections.
A checksum and expected REV count are checked for repeatability. Every mixed
workload's denominator is the count of *mutations*, not mutations plus queries.
Changing read frequency intentionally changes the workload cost.

This is a **diagnostic microbenchmark**, not JMH. It does not control CPU frequency,
thermal throttling, garbage collection, JIT compilation, system load or confidence
intervals. Tiny single-operation growth results are particularly noisy. There are
currently no evidence-based performance claims.

## Fresh-JVM first-use measurement

The separate `StartupProbe` is designed for an external process timer:

```sh
/usr/bin/time -f 'wall_s=%e rss_kb=%M' \
  java -cp target/classes emb.experiment.registration.StartupProbe REBUILD 500
```

Each invocation launches a fresh JVM, builds 128 stage starts and N rows and serves
one 32-row page. Wall time therefore includes **JVM process launch** as well as first
use, and max RSS is the **entire JVM process**, not just projection memory.
The `mainToFirstPageNs` line excludes JVM process startup. Neither should be
called isolated row-construction time.

GitHub Actions has a separately triggered **Diagnostic benchmarks** workflow.
Its CSV and startup probe logs are retained as a downloadable artifact for 30 days.
Results on an ephemeral desktop CI runner are exploration only.

## Required before an architecture decision

1. Add a faithful current-production baseline for full rank recalculation.
2. Add an independent oracle for compact/time-only start timestamps and node events.
3. Compare a packed/primitive row-state candidate with this object-row baseline.
4. Validate the bounded change journal against actual engineering display synchronization
   and reconnect behaviour, and measure change-journal costs separately.
5. Calibrate with JMH/forks/profilers for robust performance evidence.
6. Qualify the result on the actual Pi Zero using the separately maintained generic OS image.

No production files are modified by this experiment.
