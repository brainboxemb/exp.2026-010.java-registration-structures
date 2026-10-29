# Production migration map

This is the implementation map from Experiment 010 back to the current Event Timing
application. It is intentionally written before changing the production repository.

Production reference:
`brainboxemb/2026-010-02.java.timing-point-application`

## 1. Keep the existing outer ownership

Do not move LogBook, RaceData or task scheduling into the new registration structure.

The production flow remains:

```text
NodeService / DataTask
        |
        +-- LogBook            authoritative TimingRecord history
        |
        +-- RaceDataState      authoritative current stage starts / assignments
        |
        v
RegistrationList              derived retained registration state
```

All state mutations still happen on the existing serialized Node state/task lane.

## 2. Replace the inside of ActiveRegistrations, not its responsibilities

Current production `ActiveRegistrations` already represents the registration projection.
The migration should preserve that domain boundary while replacing its internal storage.

Preferred shape:

```text
ActiveRegistrations
    |
    +-- RegistrationList
          |
          +-- RegistrationIndex
          +-- SortedArrayRanking
          +-- RegistrationMutations
```

Whether the production class ultimately keeps the name `ActiveRegistrations` as a thin
wrapper or is renamed separately is a later code-style decision. The experiment does not
require a package-wide rename.

## 3. ADD adapter

Current DataTask already knows:
- committed LogBook sequence;
- RegistrationId;
- effective/passage epoch millis;
- current RaceData stage start for that RegistrationId;
- active LocationId / record-kind filter result.

The production adapter becomes conceptually:

```java
registrationList.add(
        record.sequence(),
        registrationId,
        passageEpochMillis,
        currentStageStartOrNoTime);
```

The LocationId/kind filtering stays in the production adapter/DataTask. It is not duplicated
inside RegistrationList.

## 4. REV adapter

After production filtering, a REV becomes:

```java
registrationList.revoke(
        record.sequence(),
        registrationId,
        passageEpochMillis);
```

RegistrationList itself chooses:
- direct `onlyValidRow` when one valid row exists;
- backwards scan when multiple valid rows exist.

No row is physically removed.

## 5. OPEN / historical catch-up

At OPEN/rebuild:

```text
RegistrationList new instance
beginInitialLoad()

DataTask reads LogBook from sequence 1 in current 64-record pages

for each relevant registration record:
    ADD -> resolve CURRENT RaceData start and call add(...)
    REV -> call revoke(...)

finishInitialLoad()

publish/allow first snapshot
snapshot mutation revision = 0
```

Do not generate display mutations for historical replay.

This matches current production behavior where final ranking is only finalized after catch-up.

## 6. Stage-start updates

RaceDataState remains authoritative.

For one already validated/normalized SET:

```text
RaceDataState changes current start
        |
        v
registrationList.setStart(registrationId, compactUtcStart)
```

For DEL:

```text
RaceDataState removes current start
        |
        v
registrationList.deleteStart(registrationId)
```

If RegistrationList has no retained row for that RegistrationId, these methods return false
and do not create an index entry or display mutation.

A later ADD still gets the correct current start directly from RaceData.

One IF-06 STAGE_STARTS message contains at most 16 changes, so scalar calls are sufficient.

## 7. Ranking

Replace eager whole-list `CompetitionRanking.recalculate()` maintenance with
`SortedArrayRanking`.

Registration rows do not store rank.

Any output path that needs rank does:

```text
row elapsed -> RankingIndex.rankOf(elapsed)
```

The production adapter should not introduce a second rank cache.

## 8. RegisteredTeams stays separate

Do **not** replace `RegisteredTeams` with RegistrationIndex unique-registration counts.

RegistrationIndex can answer:
- unique retained RegistrationId count;
- unique valid RegistrationId count.

Production `RegisteredTeams` resolves RegistrationId to TeamId using EventData and live
assignment state. That is a different projection and must remain correct independently.

## 9. Display/API migration

Current engineering-client projection synchronization uses:
- count;
- lastMutationSequence;
- lastRevokeSequence;
- stageStartRevision;
- full reread on REV/start invalidation.

Target flow:

```text
client requests snapshot
    <- rows + mutationRevision

client remembers cursor

client requests mutations after cursor
    <- ROW_ADDED / ROW_REVOKED / START_CHANGED

for each mutation:
    refresh direct row or registrationId rows
    OR rankingDirty when RANKING_CHANGED

if rankingDirty:
    re-read current data/rank only for rows the client currently presents

advance cursor
```

If the cursor is older than the retained mutation ring:
```text
HISTORY_EXPIRED -> request new snapshot
```

The first production integration can expose this alongside the old projection endpoint until
the engineering client proves convergence.

## 10. Suggested migration sequence

### Phase A — production fixtures only

Before changing storage, add regression fixtures around current production for:

1. automatic + manual ADD filtering;
2. other LocationId ignored;
3. same LocationId across multiple OPEN/CLOSE periods;
4. newest matching REV;
5. unmatched REV;
6. current RaceData start applied during historical rebuild;
7. time-only midnight / 12-hour boundary;
8. dated compact UTC start;
9. stage-start SET/DEL batches;
10. RegistrationId-to-TeamId team count.

### Phase B — storage behind existing read API

Introduce RegistrationList + SortedArrayRanking behind existing production projection reads.

Keep the old wire/read behavior temporarily. This isolates storage correctness from protocol
changes.

Compare old/new snapshots in tests.

### Phase C — mutation synchronization API

Add snapshot revision + bounded mutation read.

Keep it on the existing state/task lane.

Add a client-side replica test equivalent to Experiment 010
`DisplaySynchronizationTest`.

### Phase D — engineering client

Switch engineering client from full rereads on REV/stage-start changes to snapshot + mutations.

Keep explicit full-resync fallback when mutation history expires.

### Phase E — remove old ranking/projection code

Only after:
- production fixture parity;
- Pi Zero qualification;
- client convergence tests;
- at least one realistic replay trace.

## 11. What should not be migrated from the experiment

Do not copy benchmark-only concerns into production:
- RankingIndexKind factory;
- RebuildSort/TreeMap/AVL candidates;
- diagnostic counters unless useful operationally;
- benchmark visitors/checksums;
- JMH dependencies.

Production should receive only the selected/simple implementation and the required invariants.

## 12. Expected final production core

The selected core should be small:

```text
RegistrationList
RegistrationIndex
SortedArrayRanking
RegistrationMutations
StageTiming (existing production semantics)
```

plus adapters around existing:
```text
DataTask
RaceDataState
LogBook
RegisteredTeams
engineering API/client
```

This keeps the experiment result local instead of turning it into a new application
architecture.
