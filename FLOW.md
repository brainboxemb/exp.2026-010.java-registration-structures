# Registration and ranking flow

This document describes the **intended data flow** around registration ranking.

It is deliberately written before the surrounding implementation is built. The goal is
that somebody reading the repository does **not** have to reverse-engineer the architecture
from benchmark classes or ranking data structures.

## 1. Responsibilities

There are three different concerns:

```text
LogBook
   |
   |  committed TimingData records
   v
ActiveRegistrations
   |
   |  elapsed times of valid/rankable registrations
   v
RankingIndex
```

### LogBook

The LogBook remains the authoritative history.

It contains the original TimingData records, including ADD and REV records and records for
other timing points.

The ranking code must **not** become a second LogBook.

### ActiveRegistrations

`ActiveRegistrations` contains only the registration rows relevant to the currently active
timing point.

A retained registration row represents the original ADD. A REV does not create another
display row; it invalidates the matching ADD row.

A registration row should contain the data needed to understand that registration itself:

```text
sequence
registrationId
passageTime
startTime
elapsedTime
valid
```

`elapsedTime` may be cached because it changes only when the registration's start time changes.

The row deliberately does **not** contain `rank`.

### RankingIndex

`RankingIndex` contains only elapsed times that currently participate in ranking.

Conceptually:

```text
ActiveRegistrations

seq  id      passage    start      elapsed   valid
101  A0001   12:30:12   12:00:00   30:12     true
103  A0002   12:28:45   12:00:00   28:45     true
108  A0003   12:31:02   unknown    unknown   true
110  A0004   12:25:00   12:00:00   25:00     false

RankingIndex

28:45
30:12
```

A missing elapsed time is not inserted. An invalid registration is not inserted.

The ranking index may have several implementations:

- rebuild + sort;
- sorted primitive array;
- TreeMap;
- custom pooled order-statistic AVL.

Those are the implementations compared by Experiment 010.

## 2. Rank is calculated at read time

Rank is **not stored in Registration**.

The competition rank of one elapsed time is:

```text
rank = 1 + number of valid elapsed times that are strictly smaller
```

Examples:

```text
elapsed: 10, 10, 15, 18
rank:     1,  1,  3,  4
```

A visitor that reads registrations asks the ranking index for the rank at that moment:

```java
registrations.visit(registration -> {
    int rank = registration.hasElapsedTime()
            ? rankingIndex.rankOf(registration.elapsedTime())
            : 0;

    visitor.accept(registration, rank);
});
```

This is intentional.

If another runner is added or revoked, the rank of an unchanged registration can change.
Storing rank inside every registration would therefore require rewriting many unrelated rows.

## 3. Opening a timing point

When a timing point becomes active:

```text
1. create empty ActiveRegistrations
2. create empty RankingIndex
3. read/replay relevant LogBook history
4. keep only ADD rows for the active location
5. apply matching REV records to those rows
6. resolve current start time for each retained ADD
7. calculate/copy elapsedTime into each registration
8. add every valid known elapsedTime to RankingIndex
9. ActiveRegistrations + RankingIndex are now ready for visitors
```

The implementation may optimize how step 8 is built, but the resulting state must be identical.

## 4. Live ADD flow

For a new ADD at the active timing point:

```text
TimingData ADD
     |
     v
append/commit to LogBook
     |
     v
ActiveRegistrations.add(...)
     |
     +--> retain original sequence/id/passage
     |
     +--> look up current start time
     |
     +--> calculate elapsedTime
     |
     +--> if valid and elapsedTime known:
              RankingIndex.add(elapsedTime)
```

Order matters: the LogBook is authoritative history; the active list is derived live state.

## 5. Live REV flow

For a REV:

```text
TimingData REV
     |
     v
append/commit to LogBook
     |
     v
find newest still-valid ADD with same registrationId + passageTime
     |
     +--> if its elapsedTime participates in ranking:
     |        RankingIndex.remove(elapsedTime)
     |
     +--> mark retained ADD row valid = false
```

The REV itself does not become a registration row.

If no matching valid ADD exists, the active registration list and ranking index do not change.

## 6. Start-time change flow

A start-time change is different because it can change the elapsed time of existing rows.

For one registration ID:

```text
new start time for A0007
        |
        v
find retained rows with registrationId A0007
        |
        +--> for every valid row with old elapsedTime:
        |        RankingIndex.remove(oldElapsedTime)
        |
        +--> store new startTime
        |
        +--> recalculate elapsedTime
        |
        +--> for every valid row with new known elapsedTime:
                 RankingIndex.add(newElapsedTime)
```

This means the ranking index is always synchronized with the cached elapsed times in
`ActiveRegistrations`.

Deleting a start time follows the same flow: old elapsed times are removed and the affected
rows become non-rankable until another start time is known.

## 7. Visitor flow

A visitor wants registration rows, for example for an engineering client or display query.

```text
visitor requests N registration rows
        |
        v
ActiveRegistrations visits rows in ADD order
        |
        +--> row has no elapsedTime or is invalid
        |        rank = 0
        |
        +--> valid row has elapsedTime
                 rank = RankingIndex.rankOf(elapsedTime)
        |
        v
visitor receives registration data + current rank
```

The important property is that rank is evaluated **when the row is read**.

No bulk rewrite of registration rows is necessary when ranks change.

## 8. Invariants

The implementation and tests should enforce these rules:

1. LogBook is the historical source of truth.
2. ActiveRegistrations retains ADD rows for one active timing point.
3. A REV invalidates a retained ADD; it does not create a second row.
4. Every valid row with a known elapsed time contributes exactly one occurrence to RankingIndex.
5. Invalid rows contribute zero occurrences.
6. Rows with unknown elapsed time contribute zero occurrences.
7. Duplicate elapsed times remain duplicate occurrences in RankingIndex.
8. Registration rows do not store rank.
9. `rankOf(elapsed)` returns the current competition rank at read time.
10. After every ADD, REV or start-time mutation, ActiveRegistrations and RankingIndex agree.

## 9. What Experiment 010 measures

The flow above is fixed.

Experiment 010 varies **only the implementation of RankingIndex**.

A future integration test can drive the complete flow, but timing comparisons should isolate the
ranking implementation so every candidate receives exactly the same mutations.

The initial interface is therefore intentionally small:

```java
interface RankingIndex {
    void add(long elapsedMillis);
    boolean remove(long elapsedMillis);
    void prepare();
    int rankOf(long elapsedMillis);
}
```

`prepare()` exists so rebuild+sort can be compared fairly with always-ready structures.

## 10. What is not part of this experiment yet

This repository does not yet decide:

- exact production class/package names;
- LogBook persistence implementation;
- Node Task Lane scheduling;
- Central API or protocol boundaries;
- engineering-client synchronization;
- compact/time-only StageTiming conversion rules.

Those can be integrated after the ranking/list architecture has been validated.

The intended flow above should remain the reference when that integration happens.
