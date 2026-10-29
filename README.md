# Experiment 010 — Java registration and ranking structures

**Start with [FLOW.md](FLOW.md)** for the functional flow and
**[MEMORY.md](MEMORY.md)** for the concrete memory/search structures.

Together they define what belongs in the LogBook, RegistrationList,
RegistrationIndex, RankingIndex and RegistrationMutations, including how to
avoid unnecessary object churn, list copies and full-list rewrites.

The branch now contains working experimental implementations of those structures:
- `RegistrationList`: append-only primitive row arrays;
- `RegistrationIndex`: primitive per-registrationId lookup/count/start metadata;
- `RegistrationMutations`: bounded primitive mutation ring;
- interchangeable `RankingIndex` implementations.

Once that flow is clear, this repository answers one concrete implementation question:

> **Which data structure is a good fit for maintaining competition ranking for roughly 500 timing registrations in Java?**

The ranking rule is deliberately simple:

```text
rank = 1 + number of valid elapsed times that are strictly faster
```

So elapsed times `10, 10, 15, 18` produce ranks `1, 1, 3, 4`.

The experiment implements several real solutions, checks that they produce the
same answers, and then runs the same timed workloads against each solution.
It does **not** simulate the complete Timing Point application.

## Solutions

| Solution | Idea |
| --- | --- |
| `RebuildSortRanking` | Keep times in an unsorted primitive list; copy+sort when ranking is prepared. This represents the current rebuild+sort cost pattern. |
| `SortedArrayRanking` | Keep one primitive `long[]` sorted; binary-search ranks and shift values on mutations. |
| `TreeMapRanking` | Use standard Java `TreeMap<Long,Integer>` with an occurrence count per elapsed time. |
| `PooledAvlRanking` | Custom order-statistic AVL multiset with occurrence/subtree counts and a preallocated node pool. |

Two deliberately small custom list implementations are part of the experiment:

- `LongArrayList` — primitive growable list used by rebuild+sort.
- `SortedLongList` — primitive sorted growable list used by sorted-array ranking.

There is no `Projection`, display model, OPEN/CLOSE simulation or application API in
this branch. Those are separate questions.

## Correctness first

```sh
mvn -B verify
```

The tests compare every solution with an independent, intentionally slow oracle:
a normal Java list where rank is calculated by literally counting all values that
are smaller. Tests include duplicates, removals, random mutation sequences and
capacity growth.

## Timing

```sh
java -cp target/classes emb.experiment.ranking.RankingBenchmark
```

Default populations are **500, 1,000 and 5,000**. The diagnostic benchmark measures:

| Scenario | Purpose |
| --- | --- |
| `construct_512` | Cost of preallocating a normal-sized structure |
| `initial_fill` | Fill the initial registration population and prepare ranking |
| `grow_512_to_513` | Cost of crossing the normal preallocated capacity |
| `replace_and_prepare` | Remove one elapsed time, add a changed time, make ranking ready |
| `replace_prepare_and_rank` | Same mutation plus a rank lookup |
| `rank_query` | Repeated rank lookup without mutation |

The benchmark reports median nanoseconds/operation, allocated thread bytes/operation
when available, capacity and growth events.

This is an early diagnostic benchmark, not yet a JMH result. Final architecture
selection also requires repeated/forked measurements and Pi Zero qualification.

## Code reading order

Start here:

```text
FLOW.md
MEMORY.md

RegistrationList.java
RegistrationIndex.java
RegistrationMutations.java

RankingIndex.java
RebuildSortRanking.java
SortedArrayRanking.java
TreeMapRanking.java
PooledAvlRanking.java

RegistrationListTest.java
RankingTest.java

RegistrationListBenchmark.java
RankingBenchmark.java
```

The Raspberry Pi image is maintained separately. This experiment does not modify
the production Event Timing application.
