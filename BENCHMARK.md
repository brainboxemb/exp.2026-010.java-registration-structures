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

The four model choices use the same public `ProjectionModel` contract:

- **PRODUCTION_EAGER**: a normalized-time, production-**like** baseline using
  `ArrayList` of mutable row objects. On each accepted live ADD/REV, and each
  stage-start change that actually changes a retained row, it resets all ranks,
  fills and sorts a reusable `long[]`, and assigns ranks with a second full scan.
  Its display visits also scan the list in chronological order. This models the
  code currently found in `ActiveRegistrations` / `CompetitionRanking`.
- **REBUILD**: object-row array and an unsorted primitive rank index, rebuilt/sorted
  at the first rank query following rankable mutations.
- **SORTED_ARRAY**: the same object rows, but a sorted primitive `long[]` rank index
  updated incrementally with array shifts.
- **PACKED_SORTED**: parallel primitive arrays for sequences, IDs, passage times,
  elapsed times and validity (no per-row object); the same sorted-index principle.

The preallocated change journal is shared across models for comparison, but is
**experimental**, not part of the current production implementation. The
production-like baseline does not reproduce every application subsystem or
full domain timestamp conversion; it is not a literal clone. Its reported
`rowCapacity`/`rowGrowthEvents` are **estimated** `ArrayList` 1.5x
capacity behaviour, because `ArrayList` does not expose its actual capacity.
JVM allocation measurements are real thread allocation counters when supported.

Stage-starts and passages in this first slice are absolute UTC epoch milliseconds.
Production additionally supports compact UTC and time-only starts, with strict ambiguity
handling at midnight and DST boundaries. These semantics must be covered by a later
parity suite before considering production integration.

## Repeatable correctness validation

```sh
mvn -B verify
```

The independent oracle never uses any candidate ranking index and computes the
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

The default populations are 500, 1,000 and 5,000 entries. Each model reports:

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
currently no reliable **architecture-selection** performance claims. Exploratory
CSV evidence is retained as CI artifacts and should be accompanied by a
committed summary referencing the exact workflow run SHA.

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

1. Validate production-like baseline against actual production record and projection fixtures,
   including the record type / LocationId filtering and rebuild-vs-live distinction.
2. Add an independent oracle for compact/time-only start timestamps and node events.
3. Improve model-specific memory-retention attribution and verify ArrayList capacity estimates.
4. Validate the bounded change journal against actual engineering display synchronization
   and reconnect behaviour, and measure change-journal costs separately.
5. Calibrate with JMH/forks/profilers for robust performance evidence.
6. Qualify the result on the actual Pi Zero using the separately maintained generic OS image.

No production files are modified by this experiment.
