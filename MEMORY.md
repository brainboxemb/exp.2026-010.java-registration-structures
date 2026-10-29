# Memory and search structure design

This document records the intended in-memory representation for the active registration
state around Experiment 010.

The design goal is not only low asymptotic complexity. With a normal population of roughly
500 registrations, the more important goals are:

- very few temporary objects;
- no create/delete churn during normal mutations;
- no full-list copies for ordinary reads;
- no compaction when a registration is revoked;
- no second object graph that duplicates the LogBook;
- explicit, bounded growth when the normal preallocation is exceeded.

The corresponding functional flow is described in [FLOW.md](FLOW.md).

## 1. Overall layout

The active state is deliberately split into three structures:

```text
ActiveRegistrations
    |
    +-- RegistrationLookup
    |
    +-- RankingIndex
    |
    +-- RegistrationMutations
```

Each structure has one job.

- **ActiveRegistrations** stores retained ADD rows in chronological order.
- **RegistrationLookup** accelerates the common lookup by registrationId.
- **RankingIndex** stores only elapsed times that currently participate in ranking.
- **RegistrationMutations** is a bounded synchronization journal for displays.

None of these structures replaces the LogBook.

## 2. ActiveRegistrations: append-only primitive row storage

The preferred representation is a custom structure-of-arrays list rather than one Java
object per row.

Conceptually the **row arrays** are:

```text
long[] sequence
int[]  registrationId
long[] passageTime
long[] elapsedTime
byte[] flags
```

The current start time is owned by ActiveRegistrations too, but is stored **once per
registrationId in RegistrationLookup**, not duplicated in every row.

Logically a visitor can still see:

```text
sequence + registrationId + passageTime + current startTime + elapsedTime + valid
```

Physical storage and the logical row view do not have to be identical.

The same array index identifies one retained registration across all arrays.

For example row 217 is:

```text
sequence[217]
registrationId[217]
passageTime[217]
elapsedTime[217]
flags[217]

startTime = RegistrationLookup.startTimeFor(registrationId[217])
```

Suggested flags:

```text
VALID
HAS_START
HAS_ELAPSED
```

If a single sentinel value is safe for start/elapsed, the HAS_* flags can be omitted.
The production time representation should decide that later.

### Why append-only?

ADD appends one new row.

REV does **not** remove or shift a row. It only clears the VALID flag.

That gives:

- stable row positions;
- no array shifts on REV;
- no per-REV allocation;
- no need to update indexes because positions moved;
- chronological ADD order for visitors for free.

The list is cleared/replaced only when another timing point becomes active.

### Capacity

Normal expected size is about 500 rows, so a natural first capacity is 512.

When exceeded, grow geometrically, for example:

```text
512 -> 1024 -> 2048
```

A growth event copies each primitive array once. This is acceptable because it is rare,
visible and measurable. Normal ADD/REV processing should not copy the complete list.

The implementation should expose a growth counter in the experiment so boundary costs are
not hidden.

## 3. RegistrationLookup: fast common case without a complex secondary list

The lookup is keyed by packed `registrationId`.

For each unique id it retains only small scalar metadata:

```text
registrationId -> {
    retainedCount
    validCount
    onlyValidRow
}
```

Meanings:

- `retainedCount`: number of ADD rows for this id in ActiveRegistrations;
- `validCount`: number of those rows currently valid;
- `onlyValidRow`: direct ActiveRegistrations row index when `validCount == 1`.

No list of all row positions is retained.

### REV lookup

Normal case:

```text
validCount == 0
    -> no matching valid registration

validCount == 1
    -> inspect onlyValidRow directly
    -> if passageTime also matches, revoke immediately

validCount > 1
    -> exceptional case
    -> scan ActiveRegistrations backwards for newest matching valid row
```

With around 500 rows, an occasional complete backwards scan is intentionally preferred over
maintaining another variable-size collection per registrationId.

When a REV changes `validCount` from 2 to 1, one scan may be used to rediscover the remaining
valid row and restore `onlyValidRow`.

### Primitive hash table

The preferred lookup implementation is an open-addressed primitive hash table:

```text
int[]  keys
int[]  retainedCount
int[]  validCount
int[]  onlyValidRow
long[] startTime
```

No `Map<Integer,...>`, boxed keys or entry objects are required.

Packed registrationId zero is not a valid registration id, so `keys[slot] == 0` can be
used as the EMPTY marker. No separate `occupied[]` array is required.

Because entries are not deleted while a WP is active, the table does not need tombstones.
A registrationId that becomes invalid remains a known key with `validCount == 0`.

For approximately 500 unique ids, an initial table capacity of 1024 keeps the load factor
comfortably below 0.5 and avoids rehashing in the normal case.

The lookup directly provides:

- `uniqueRetainedRegistrationCount = non-empty key count`;
- `uniqueValidRegistrationCount = entries with validCount > 0`;
- current start time for each registrationId.

Maintain both unique counts incrementally. In particular, update the valid-id count only on
the `validCount 0 -> 1` and `1 -> 0` transitions; do not scan the table to answer it.

Maintain the second count incrementally on the 0->1 and 1->0 transitions; do not rescan the
table just to answer the count.

## 4. RankingIndex: elapsed values only

Ranking is intentionally separate from the registration rows.

The index contains one occurrence for every row satisfying:

```text
valid && hasElapsedTime
```

It does not store registrationId, row position, passage time or rank.

The current experiment compares:

- rebuild + sort;
- sorted primitive array;
- TreeMap reference;
- custom pooled order-statistic AVL.

The selected production implementation should be preallocated for the normal case and should
not allocate on every ADD/REV.

Rank itself is never written back into ActiveRegistrations.

A visitor obtains:

```java
rank = rankingIndex.rankOf(elapsedTime);
```

at read time.

## 5. Start-time mutation: scan instead of maintaining another index

A start-time change for one registrationId may affect more than one retained ADD row.

Do not add another per-id list of row positions merely to optimize this rare operation.

Instead:

```text
RegistrationLookup.startTime[id] = new start

scan ActiveRegistrations
    for rows with registrationId == changed id:
        if valid + old elapsed:
            RankingIndex.remove(old elapsed)

        recalculate cached elapsed from passage + new start

        if valid + new elapsed:
            RankingIndex.add(new elapsed)
```

At roughly 500 rows this scan is cheap, allocation-free and simple.

Because start time is stored once in RegistrationLookup, this scan changes only cached elapsed
values in the affected rows; it does not rewrite a duplicated start value into every row.

The benchmark should include this operation before deciding that another index is justified.

## 6. RegistrationMutations: preallocated ring buffer

Display synchronization needs a mutation history, but it should not allocate one event object
per change.

Use a primitive circular buffer.

A compact representation can be:

```text
byte[] kind
int[]  subject
byte[] flags

long latestRevision
```

`subject` is interpreted by `kind`:

- ROW_ADDED / ROW_REVOKED: stable ActiveRegistrations row index;
- START_CHANGED: packed registrationId.

This avoids keeping both `row[]` and `registrationId[]` in every mutation slot.

The revision number itself does not need an array when slot position can be derived from the
monotonic revision:

```text
slot = revision % capacity
```

A query is accepted only when its cursor is within the retained revision window, so overwritten
slots are never interpreted as old revisions.

Suggested mutation kinds:

```text
ROW_ADDED
ROW_REVOKED
START_CHANGED
```

The mutation is intentionally a **dirty/current-state marker**, not a second historical event
record. The LogBook already contains the history.

- ADD / REV identify the stable row index;
- START_CHANGED identifies the registrationId;
- flags can indicate that rank-dependent presentation should be refreshed.

When a consumer reads the mutation, it reads the current row/start data from
ActiveRegistrations. If ADD and REV both happened before the consumer caught up, receiving the
current invalid row is sufficient to converge to the correct current state; the display does
not need to reconstruct every intermediate visual state.

The mutation list is a **change/invalidation journal**, not a second copy of registration data.
When a consumer visits a mutation it can read the current row data from ActiveRegistrations.

This avoids copying sequence/id/time/etc. into every mutation record.

### Bounded history

For example, preallocate 512 or 1024 mutation slots.

Expose:

```text
latestRevision
oldestAvailableRevision
visitAfter(revision, limit)
```

When a cursor is too old:

```text
history expired -> new snapshot
```

Do not grow the mutation list forever.

## 7. Snapshot without building a Java List

A snapshot must represent one serialized state point together with its mutation marker.

Avoid:

```java
new ArrayList<SnapshotRow>()
new SnapshotRow(...) for every registration
```

unless the transport layer absolutely requires detached objects.

Preferred flow on the single state/task lane:

```text
begin serialized snapshot operation

snapshotRevision = RegistrationMutations.latestRevision()
rowCount = ActiveRegistrations.size()

visit rows directly
    -> write directly into snapshot encoder / response buffer
    -> calculate rank from RankingIndex while visiting

finish serialized snapshot operation

send encoded result outside state mutation logic
```

The snapshot header contains:

```text
rowCount
mutationRevision
```

The row data is emitted directly from the primitive arrays.

This still necessarily copies bytes into the outgoing response, but it avoids:

- copying the whole registration model into another list;
- creating one temporary Java object per row;
- creating a second object graph only to serialize it.

If the eventual protocol requires paging, the same principle applies: visit a bounded range
directly and encode it without materializing a temporary list.

### Snapshot consistency

Reading the marker and encoding the rows must belong to the same serialized state operation.
Otherwise a live mutation could occur between the two and make the marker ambiguous.

Do not return a pointer to mutable ActiveRegistrations and encode it asynchronously after the
task lane has resumed. Either encode while state is stable or introduce an explicit immutable
snapshot representation. The former is preferred for ~500 rows unless measurement shows the
serialization stalls the state lane too long.

## 8. Visitors instead of copies

Read APIs should prefer visitors/callbacks over collection-returning methods.

Preferred:

```java
int visitAfter(int row, int limit, RegistrationVisitor visitor)
```

Not:

```java
List<Registration> registrationsAfter(...)
```

The visitor receives scalar values directly from the primitive arrays.

Likewise:

```java
int visitMutationsAfter(long revision, int limit, MutationVisitor visitor)
```

should walk the ring buffer directly.

This keeps allocation under caller control.

## 9. Object/allocation policy

Normal operation should create **no new model objects** for:

- REV;
- rank lookup;
- visitor reads;
- mutation append;
- mutation iteration;
- changing a start time;
- snapshot row iteration.

ADD should normally only write into preallocated arrays. If capacity is exceeded, array growth
is allowed and measured.

TreeMap remains useful in the experiment as a standard-library comparison, but it is not the
preferred memory shape because of boxed keys/counts and tree-node churn.

The custom AVL may preallocate nodes, but for the expected ~500 entries the sorted primitive
array is currently the simpler candidate and should remain the default comparison point.

## 10. Expected operation costs

For normal capacity:

| Operation | ActiveRegistrations | RegistrationLookup | RankingIndex | Allocation |
| --- | --- | --- | --- | --- |
| ADD | O(1) append | O(1) average | candidate-dependent | none |
| REV, one valid row | O(1) direct row | O(1) average | candidate-dependent | none |
| REV, multiple valid rows | O(N) reverse scan | O(1) average | candidate-dependent | none |
| Start change | O(N) scan | O(1) lookup | K removals/adds | none |
| Visit N rows | O(N) requested rows | none | N rank queries | none |
| Append mutation | none | none | none | O(1), none |
| Visit mutation batch | none | none | none | O(batch), none |
| Snapshot | O(N) direct encode | none | N rank queries | response bytes only |

Here `N` is normally around 500 and `K` is the number of affected valid rows for one
registrationId.

## 11. What should be measured

Before adding more indexes or caches, measure:

1. active-row scan time at 500 / 1,000 / 5,000 rows;
2. REV fast path versus multiple-registration slow path;
3. start-time update scan;
4. RankingIndex mutation/query costs;
5. mutation-ring append/read costs;
6. full snapshot direct-encoding time;
7. allocation bytes during steady state;
8. capacity-boundary costs at 512->513 and lookup/ring growth boundaries.

An optimization should only be added when one of these measurements shows that the simple
structure is inadequate.

## 12. Invariants

The implementation should test these invariants continuously:

1. row positions never change while a WP is active;
2. REV never physically removes a row;
3. lookup `retainedCount` equals the number of retained rows for that id;
4. lookup `validCount` equals the number of valid rows for that id;
5. when `validCount == 1`, `onlyValidRow` points to that row;
6. RankingIndex occurrence count equals the number of valid rows with known elapsed time;
7. mutation revisions are strictly monotonic;
8. snapshots carry the mutation revision of the exact serialized state;
9. expired mutation history always forces a new snapshot;
10. ordinary visitors do not allocate result lists.
