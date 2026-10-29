# Start here: how the experiment works

This is **not an application**. It is a small laboratory for answering one
question: *how should the Timing Point retain registration rows, update rankings
and expose changed rows to displays without excessive allocation or CPU cost?*

The actual Timing Point software lives in a different repository. Nothing in
this experiment starts the Timing Point, connects to Central, or processes real
network messages.

## The problem in plain language

Imagine a timing point with these committed LogBook facts:

| LogBook sequence | WP | Action | Bib | Passage time |
| ---: | ---: | --- | --- | --- |
| 1 | 1 | ADD | A0001 | 12:00:10 |
| 2 | 2 | ADD | A0002 | 12:00:20 |
| 3 | 1 | ADD | A0003 | 12:00:15 |
| 4 | 1 | REV | A0001 | 12:00:10 |

When WP 1 opens, its display projection contains the two ADD rows from WP 1.
The A0001 row is still present (in chronological order) but marked invalid.
The row from WP 2 is excluded. REV has **no display row of its own**.

A known start time makes an ADD passage rankable. Faster elapsed times rank
first; ties have equal rank. For elapsed times 10, 10 and 15 seconds, ranks
are 1, 1 and 3. When a start time changes, the relevant elapsed times and
possibly every displayed rank need to be reconsidered.

## Follow the code in this order

**1. `LogBookProjectionSession.java`** — A *test fixture* that resembles the
surrounding app workflow. A LogBook keeps historical records; `open(wp)`
creates a fresh projection and replays only relevant facts. `close()`
discards the active view, but not historical LogBook facts. Updates during
replay must not send display change events.

**2. `ProjectionModel.java`** — The tiny common API that all four alternatives
implement. Learn these operations first: `add`, `revoke`, `setStart`,
`visitAfter`. `beginRebuild` / `completeRebuild` bracket silent replay.

**3. `ModelKind.java`** — Selects which implementation the same test will use.

**4. The four candidates:**
- `ProductionBaseline.java`: approximate the *current production algorithm*.
  Retain Java objects in an `ArrayList`; after a live mutation, recalculate
  ranks for all rows. It is **not** a copy of the complete application.
- `RegistrationProjection.java` (`REBUILD`): same logical rows, but delay
  sorting rank values until rank information is requested.
- `RegistrationProjection.java` (`SORTED_ARRAY`): maintain a sorted rank
  index when a mutation arrives so rank queries are cheap.
- `PackedProjection.java`: use arrays of primitive values rather than
  allocating a separate row object per ADD; also maintain a sorted rank index.

**5. `ChangeJournal.java`** — An *experimental* fixed-size circular log of
changed registration rows and rank invalidations. A display that misses too
many changes receives an explicit "history expired" signal; it then needs
a full snapshot. **The production app does not currently have this journal.**

**6. Tests** — Read `LogBookProjectionSessionTest.java` for a concrete
OPEN / ADD / REV / CLOSE scenario. Read `RegistrationOracleTest.java`
to see the independent rule-based checks of the four candidates.

**7. Measurement code last** — `DiagnosticBenchmark.java` and
`StartupProbe.java` run artificial repeatable workloads and report
time/allocation diagnostics. They are intentionally *not* part of an actual
timing-point service.

## How a live update flows

```text
           authoritative history
                  LogBook
                     |
            OPEN: replay history
                     v
         active WP registration view
        (one of four ModelKind variants)
                     |
           +---------+----------+
           |                    |
        full snapshot       live mutation
      visitAfter(cursor)    ADD / REV / start
                                |
                           update rows
                           update ranks
                                |
                          ChangeJournal
                                |
                 display reads changed events
                 or resynchronizes if expired
```

`ProjectionModel` is the view being compared, not an external protocol.
The diagram above describes the **experiment**. It does not establish
production network/API boundaries.

## What the experiments *do not* prove yet

Production stores UTC registration times as epoch milliseconds, but **stage
starts are compact UTC values**, sometimes without an explicit date. The
experiment currently takes absolute epoch-millisecond starts and does not
model DST ambiguity or midnight rollover. The experiment also does not
reproduce the exact LogBook persistence, Node task lane, Central API,
engineering client, or display update protocol.

Do not import any of these classes into production. Once the experiment has
correctness evidence and reliable hardware performance data, changes to
production are a separate task and review.

## Running tests

```sh
mvn -B verify
```

`mvn verify` is the best starting point; no manual input, Raspberry Pi or
network connection is needed. Once the tests make sense, the optional
diagnostic tool can be run with:

```sh
java -cp target/classes emb.experiment.registration.DiagnosticBenchmark 500
```

Numbers from a laptop or GitHub Actions cannot be directly taken as Raspberry
Pi Zero results. The details and limitations are in [BENCHMARK.md](BENCHMARK.md).
