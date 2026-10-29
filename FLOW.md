# Registration and ranking flow

This document describes the **intended data flow** around registration ranking.

It is deliberately written before the surrounding implementation is built. The goal is
that somebody reading the repository does **not** have to reverse-engineer the architecture
from benchmark classes or ranking data structures.

## 1. Responsibilities

The state is split into a historical source plus three small in-memory helpers:

```text
LogBook                         authoritative history
   |
   v
RegistrationList               retained ADD rows for active WP
   |
   +-- RegistrationIndex       counts / onlyValidRow / current start
   |
   +-- RankingIndex            valid known elapsed times
   |
   +-- RegistrationMutations   live display-sync changes
```

RegistrationList is the primary in-memory registration storage. The other three structures
only accelerate lookup/ranking/synchronization and do not duplicate the LogBook.

### LogBook

The LogBook remains the authoritative history.

It contains the original TimingData records, including ADD and REV records and records for
other timing points.

The ranking code must **not** become a second LogBook.

### RegistrationList

`RegistrationList` contains only the registration rows relevant to the currently active
timing point.

A retained registration row represents the original ADD. A REV does not create another
display row; it invalidates the matching ADD row.

A logical registration view contains:

```text
sequence
registrationId
passageTime          absolute UTC epoch millis
startTime            compact UTC yyyyMMddHHmmsscc or HHmmsscc
elapsedTime          cached derived milliseconds
valid
```

`startTime` and `elapsedTime` are both cached in every retained row so a visitor/snapshot
can read one row without another index lookup. `startTime` uses the same compact UTC
representation as production RaceData; `passageTime` remains absolute epoch milliseconds.
RegistrationIndex also retains the **current** compact start per registrationId so a later ADD
can initialize its row directly.

The row deliberately does **not** contain `rank`.

### RankingIndex

`RankingIndex` contains only elapsed times that currently participate in ranking.

Conceptually:

```text
RegistrationList

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
1. create empty RegistrationList + RankingIndex + RegistrationMutations
2. call RegistrationList.beginInitialLoad()
3. read/replay relevant LogBook history
4. keep only ADD rows for the active location
5. apply matching REV records to those rows
6. resolve/cache startTime + elapsedTime for each retained ADD
7. add every valid known elapsedTime to RankingIndex
8. call RegistrationList.finishInitialLoad()
9. historical replay has produced **zero display mutations**
10. take the first snapshot; its mutation marker is 0
11. only subsequent live changes create mutation revisions 1, 2, 3...
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
RegistrationList.add(...)
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

If no matching valid ADD exists, the registration list and ranking index do not change.

## 6. Start-time change flow

A start-time change is different because it can change the elapsed time of existing rows.

For one registration ID:

```text
new start time for A0007
        |
        v
store new current startTime in RegistrationIndex
        |
        v
find retained rows with registrationId A0007
        |
        +--> for every valid row with old elapsedTime:
        |        RankingIndex.remove(oldElapsedTime)
        |
        +--> cache new startTime in each affected row
        |
        +--> recalculate elapsedTime
        |
        +--> for every valid row with new known elapsedTime:
                 RankingIndex.add(newElapsedTime)
```

This means the ranking index is always synchronized with the cached elapsed times in
`RegistrationList`.

Deleting a start time follows the same flow: old elapsed times are removed and the affected
rows become non-rankable until another start time is known.

## 7. Visitor flow

A visitor wants registration rows, for example for an engineering client or display query.

```text
visitor requests N registration rows
        |
        v
RegistrationList visits rows in ADD order
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
2. RegistrationList retains ADD rows for one active timing point.
3. A REV invalidates a retained ADD; it does not create a second row.
4. Every valid row with a known elapsed time contributes exactly one occurrence to RankingIndex.
5. Invalid rows contribute zero occurrences.
6. Rows with unknown elapsed time contribute zero occurrences.
7. Duplicate elapsed times remain duplicate occurrences in RankingIndex.
8. Registration rows do not store rank.
9. `rankOf(elapsed)` returns the current competition rank at read time.
10. After every ADD, REV or start-time mutation, RegistrationList and RankingIndex agree.


## 9. Fast registration lookup and unique-registration count

`RegistrationList` remains a chronological list of retained ADD rows.

For the normal lookup case it may additionally keep one small entry per
`registrationId`:

```text
registrationId -> {
    retainedCount
    onlyRetainedRow
    validCount
    onlyValidRow
}
```

Meaning:

```text
retainedCount = 0   no retained ADD row
retainedCount = 1   onlyRetainedRow gives direct start/sync row
retainedCount > 1   start/sync uses the exceptional list scan

validCount = 0      no currently valid ADD
validCount = 1      onlyValidRow gives direct REV row
validCount > 1      REV uses the exceptional reverse scan
```

When a REV changes a registrationId from multiple valid rows back to exactly
one, a one-time list scan may be used to rediscover `onlyValidRow`.

This deliberately avoids maintaining a second complex list of positions.
With roughly 500 retained registrations, scanning the complete list in the
exceptional case is expected to be cheap and should be measured rather than
optimized prematurely.

Because this lookup has one entry per known `registrationId`, it also gives
us a unique-registration count without scanning every row.

Two counts must remain distinct:

- **unique retained registrations**: number of registrationIds represented by
  retained ADD rows, including an id whose rows are currently all invalid;
- **unique currently valid registrations**: number of lookup entries with
  `validCount > 0`.

The implementation should name whichever count it exposes explicitly so these
two meanings cannot be confused.

## 10. Display synchronization: snapshot plus mutation list

The display does not need the complete LogBook. It needs:

1. an occasional full snapshot of `RegistrationList`;
2. all display-relevant mutations that happened after that snapshot.

Therefore the active state also owns a monotonically numbered
`RegistrationMutations` list:

```text
RegistrationList
       |
       +---- RankingIndex
       |
       +---- RegistrationMutations
```

Every display-relevant **live** mutation gets one increasing mutation revision.
Historical LogBook replay during `beginInitialLoad()/finishInitialLoad()` is deliberately
silent; the first full snapshot is the synchronization baseline.

For example:

```text
revision 101  ADD     original ADD sequence 450
revision 102  REV     affects ADD sequence 392
revision 103  START   affects registrationId A0007
```

The exact mutation payload can be refined later. The essential property is
that revisions are strictly increasing and provide a stable synchronization
cursor.

### Taking a snapshot

Snapshot creation and obtaining its mutation marker must happen as one
serialized operation:

```text
                 one serialized state operation
              +--------------------------------+
              |                                |
              |  read RegistrationList      |
              |  snapshotRevision = current    |
              |      mutation revision         |
              |                                |
              +--------------------------------+
```

The result sent to the display is conceptually:

```text
RegistrationSnapshot {
    rows
    mutationRevision = 103
}
```

This means:

> The snapshot describes state through mutation revision 103.

The display remembers `103`.

### Synchronizing after the snapshot

After receiving the snapshot:

```text
snapshot revision = 103
          |
          v
request mutations > 103
          |
          +--> revision 104
          +--> revision 105
          +--> revision 106
          ...
```

If more live changes arrive while these mutations are being transferred, they
receive later revisions and are read in a subsequent batch. No special
snapshot rebuild is needed just because the system continues to change.

The display advances its own cursor only after it has successfully applied a
mutation/batch.

### Taking a later snapshot

At any time a new full snapshot may replace the old synchronization base:

```text
old snapshot marker = 103
mutations applied through 147

new snapshot taken
new snapshot marker = 152
```

Once the new snapshot is accepted, that display no longer needs mutations at
or before revision 152.

This is why the snapshot contains a **mutation marker**, not a registration
sequence number. Registration ADD sequence and display mutation revision have
different meanings.

### Bounded mutation history

The mutation list does not have to grow forever. A bounded ring buffer is a
reasonable implementation.

It should expose at least:

```text
latestRevision
oldestAvailableRevision
visitAfter(revision, limit)
```

If a display asks for a revision older than `oldestAvailableRevision`, the
mutation history is incomplete. The correct response is explicit:

```text
mutation history expired
        |
        v
take/request a new full snapshot
```

Never silently continue with an incomplete mutation stream.

With multiple displays, each display keeps its own snapshot/mutation cursor.
The server-side mutation list therefore cannot normally discard history merely
because one display has taken a new snapshot; retention is governed by the
bounded history policy (or later by the oldest connected consumer cursor if
we deliberately implement that optimization).

### What counts as a display mutation

At minimum:

- ADD of a retained registration;
- REV that changes a retained ADD from valid to invalid;
- start-time SET/DELETE that changes cached start/elapsed information for one
  or more retained rows.

A rank change by itself does **not** require rewriting or emitting mutations
for every affected registration. Rank is calculated at visitor/read time from
the current `RankingIndex`.

A start-time mutation may therefore identify the affected registrationId or
affected rows, while the visitor still obtains each current rank dynamically.

### Ranking-changed flag

ADD, REV and start changes can alter the rank of registrations whose own row did not change.

Do **not** emit one synthetic mutation for every row whose numeric rank changed. Instead, a
display-relevant mutation carries the `RANKING_CHANGED` flag when RankingIndex content changed.

The display processes one mutation batch like this:

```text
rankingDirty = false

for each mutation in batch:
    apply/refresh its direct row(s)
    rankingDirty |= mutation.RANKING_CHANGED

if rankingDirty:
    re-read rank/current data for rows the display is actually presenting

advance mutation cursor
```

For example, a display showing only 20 registrations refreshes those 20 ranks after one ADD;
it does not synchronize all ~500 rows merely because their possible rank relationship changed.

A full snapshot already contains current ranks for every visited row, so no extra ranking
refresh is needed after accepting that snapshot.

This preserves convergence while keeping the mutation journal compact: row/state changes are
journaled once, and rank remains derived data.

## 11. Resulting state model

The intended in-memory model is now:

```text
LogBook                         authoritative history
   |
   v
RegistrationList             retained ADD rows for active WP
   |
   +-- RegistrationIndex       retained/valid counts + direct single-row positions + start
   |        |
   |        +-- unique registration counts
   |
   +-- RankingIndex             valid known elapsed times only
   |
   +-- RegistrationMutations    ordered display synchronization changes
```

The important separation is:

- registration rows describe registrations;
- the ranking index answers current rank;
- the mutation list describes what changed since a display snapshot.

None of these structures replaces the LogBook.

## 12. What Experiment 010 measures

The flow above is fixed.

Experiment 010 keeps the RegistrationList / RegistrationIndex / RegistrationMutations design
fixed and varies the **RankingIndex implementation**.

The repository also measures the fixed list/index/mutation structures themselves: direct REV,
exceptional multi-registration scans, start-time scans, snapshot rows and mutation batches.
This lets us verify that the surrounding memory/search design stays cheap while every ranking
candidate receives the same logical mutations.

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

## 13. Memory/storage implementation rules

The concrete allocation and search-structure choices are documented in
[MEMORY.md](MEMORY.md). Exact production record/rebuild/timing semantics are documented in
[PRODUCTION-PARITY.md](PRODUCTION-PARITY.md).

Key rules:

- RegistrationList is append-only while a WP is active;
- REV invalidates a stable row instead of deleting/shifting it;
- row bulk data is stored in primitive arrays;
- current start time is retained in RegistrationIndex and cached in each row;
- the lookup keeps validCount + onlyValidRow for the common O(1) REV path;
- exceptional multi-registration cases may scan the ~500-row list;
- RankingIndex contains only rankable elapsed times;
- RegistrationMutations is a preallocated primitive ring buffer;
- visitors stream scalar values and do not build temporary result lists;
- snapshot creation directly encodes the stable state when possible rather than first copying
  it into another Java object graph.

## 14. What is not part of this experiment yet

This repository does not yet decide:

- exact production class/package names;
- LogBook persistence implementation;
- Node Task Lane scheduling;
- Central API or protocol boundaries;
- engineering-client synchronization;
- compact/time-only StageTiming conversion rules.

Those can be integrated after the ranking/list architecture has been validated.

The intended flow above should remain the reference when that integration happens.
