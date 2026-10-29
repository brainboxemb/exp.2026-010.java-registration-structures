package emb.experiment.registration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Experimental, NORMALIZED-TIME rendition of the current production design:
 * an ArrayList of mutable object rows plus an eager whole-projection ranking pass.
 *
 * The ranking pass follows the production CompetitionRanking algorithm:
 * clear ranks, gather valid elapsed times into a reusable long[] scratch array,
 * sort the prefix, then visit all rows again and binary-search the first equal
 * elapsed time to assign competition ranks.
 *
 * This is a reference baseline, NOT a copy of the production application:
 * production has StageTiming compact/time-only rules and other integrations.
 */
public final class ProductionBaseline implements ProjectionModel {
    private static final long MISSING = RegistrationProjection.MISSING;
    private static final int CHANGE_CAPACITY = 256;

    private static final class Entry {
        final long sequence;
        final int registrationId;
        final long passage;
        long start;
        boolean valid = true;
        int rank;

        Entry(long sequence, int registrationId, long passage, long start) {
            this.sequence = sequence;
            this.registrationId = registrationId;
            this.passage = passage;
            this.start = start;
        }

        long elapsed() {
            return start != MISSING && passage >= start ? passage - start : MISSING;
        }
    }

    private final List<Entry> rows;
    private final Map<Integer, Long> stageStarts = new HashMap<Integer, Long>(1024);
    private final ChangeJournal changes;
    private long[] rankScratch = new long[0];

    // ArrayList's capacity is not accessible publicly; this is an estimate using
    // its usual 1.5x growth rule, NOT an observed allocation/capacity counter.
    private int estimatedListCapacity;
    private int estimatedListGrowthEvents;
    private int rankScratchGrowthEvents;
    private int rankableCount;
    private boolean rebuilding;
    private long stateRevision;
    private long rankingRevision;
    private long lastMutationSequence;
    private long lastRevokeSequence;

    public ProductionBaseline(int initialCapacity) {
        this(initialCapacity, CHANGE_CAPACITY);
    }

    public ProductionBaseline(int initialCapacity, int historyCapacity) {
        if (initialCapacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.rows = new ArrayList<Entry>(initialCapacity);
        this.estimatedListCapacity = initialCapacity;
        this.changes = new ChangeJournal(historyCapacity);
    }

    @Override public void beginRebuild() {
        if (!rows.isEmpty() || rebuilding)
            throw new IllegalStateException("rebuild requires a newly created empty projection");
        rebuilding = true;
    }

    @Override public void completeRebuild() {
        if (!rebuilding) throw new IllegalStateException("rebuild was not started");
        // Production ActiveRegistrations.completeRebuild() recalculates only once.
        recalculateRanking();
        rebuilding = false;
        changes.resetToRevision(stateRevision);
    }

    @Override public boolean add(long sequence, int id, long passageMillis) {
        if (sequence <= lastMutationSequence || passageMillis < 0)
            throw new IllegalArgumentException("ADD sequence must increase and time must be nonnegative");
        if (rows.size() == estimatedListCapacity) {
            estimatedListCapacity += Math.max(1, estimatedListCapacity / 2);
            estimatedListGrowthEvents++;
        }
        Long start = stageStarts.get(id);
        Entry row = new Entry(sequence, id, passageMillis, start == null ? MISSING : start.longValue());
        rows.add(row);

        // Production recalculates after every live ADD, even without a stage start.
        if (!rebuilding) recalculateRanking();
        else if (row.elapsed() != MISSING) rankableCount++;
        boolean changedRank = row.elapsed() != MISSING;
        if (changedRank) rankingRevision++;
        lastMutationSequence = sequence;
        stateRevision++;
        if (!rebuilding)
            changes.record(stateRevision, ChangeJournal.Kind.ADDED, id,
                    sequence, sequence, changedRank);
        return true;
    }

    @Override public boolean revoke(long sequence, int id, long passageMillis) {
        if (sequence <= lastMutationSequence)
            throw new IllegalArgumentException("REV sequence must increase");
        for (int i = rows.size() - 1; i >= 0; i--) {
            Entry row = rows.get(i);
            if (row.valid && row.registrationId == id && row.passage == passageMillis) {
                boolean changedRank = row.elapsed() != MISSING;
                row.valid = false;
                // Production recomputes after every matching live REV.
                if (!rebuilding) recalculateRanking();
                else if (changedRank) rankableCount--;
                if (changedRank) rankingRevision++;
                lastMutationSequence = sequence;
                lastRevokeSequence = sequence;
                stateRevision++;
                if (!rebuilding)
                    changes.record(stateRevision, ChangeJournal.Kind.REVOKED, id,
                            sequence, row.sequence, changedRank);
                return true;
            }
        }
        return false;
    }

    @Override public boolean setStart(int id, long startEpochMillis) {
        if (startEpochMillis < 0) throw new IllegalArgumentException("start must be nonnegative");
        Long old = stageStarts.put(id, startEpochMillis);
        if (old != null && old.longValue() == startEpochMillis) return false;
        boolean changedRank = applyStart(id, startEpochMillis);
        stateRevision++;
        if (!rebuilding)
            changes.record(stateRevision, ChangeJournal.Kind.START_SET, id,
                    0L, 0L, changedRank);
        return true;
    }

    @Override public boolean deleteStart(int id) {
        if (stageStarts.remove(id) == null) return false;
        boolean changedRank = applyStart(id, MISSING);
        stateRevision++;
        if (!rebuilding)
            changes.record(stateRevision, ChangeJournal.Kind.START_DELETED, id,
                    0L, 0L, changedRank);
        return true;
    }

    private boolean applyStart(int id, long start) {
        boolean changedAnyRow = false;
        boolean changedRank = false;
        for (Entry row : rows) {
            if (row.registrationId != id || row.start == start) continue;
            long oldElapsed = row.elapsed();
            row.start = start;
            long newElapsed = row.elapsed();
            changedAnyRow = true;
            if (row.valid && oldElapsed != newElapsed) changedRank = true;
        }
        if (changedAnyRow && !rebuilding) recalculateRanking();
        if (changedRank && rebuilding) {
            // Count only transitions, including missing -> rankable and vice versa.
            // This avoids any O(N log N) sorting while rebuilding history.
            int count = 0;
            for (Entry entry : rows) if (entry.valid && entry.elapsed() != MISSING) count++;
            rankableCount = count;
        }
        if (changedRank) rankingRevision++;
        return changedRank;
    }

    private void recalculateRanking() {
        int count = 0;
        if (rankScratch.length < rows.size()) {
            int grown = Math.max(rows.size(), Math.max(16, rankScratch.length * 2));
            rankScratch = Arrays.copyOf(rankScratch, grown);
            rankScratchGrowthEvents++;
        }
        for (Entry row : rows) {
            row.rank = 0;
            long elapsed = row.elapsed();
            if (row.valid && elapsed != MISSING) rankScratch[count++] = elapsed;
        }
        Arrays.sort(rankScratch, 0, count);
        for (Entry row : rows) {
            long elapsed = row.elapsed();
            if (row.valid && elapsed != MISSING) {
                int low = 0;
                int high = count;
                while (low < high) {
                    int middle = (low + high) >>> 1;
                    if (rankScratch[middle] < elapsed) low = middle + 1;
                    else high = middle;
                }
                row.rank = low + 1;
            }
        }
        rankableCount = count;
    }

    // Faithful to production ActiveRegistrations.visitAfter: a chronological scan.
    @Override public int visitAfter(long afterSequence, int limit, RegistrationProjection.RowVisitor visitor) {
        if (afterSequence < 0L || limit < 0 || visitor == null)
            throw new IllegalArgumentException("invalid display query");
        if (rebuilding) throw new IllegalStateException("historical replay incomplete");
        int visited = 0;
        for (Entry row : rows) {
            if (visited >= limit) break;
            if (row.sequence > afterSequence) {
                visitor.accept(row.sequence, row.registrationId, row.passage,
                        row.elapsed(), row.valid, row.rank);
                visited++;
            }
        }
        return visited;
    }

    @Override public int visitChangesAfter(long revision, int limit, ChangeJournal.Visitor visitor) {
        return changes.visitAfter(revision, limit, visitor);
    }

    @Override public int size() { return rows.size(); }
    @Override public int rankableCount() { return rankableCount; }
    @Override public int rowCapacity() { return estimatedListCapacity; }
    @Override public int rowGrowthEvents() { return estimatedListGrowthEvents; }
    @Override public int rankGrowthEvents() { return rankScratchGrowthEvents; }
    @Override public long stateRevision() { return stateRevision; }
    @Override public long rankingRevision() { return rankingRevision; }
    @Override public long lastMutationSequence() { return lastMutationSequence; }
    @Override public long lastRevokeSequence() { return lastRevokeSequence; }
    @Override public long oldestUsableChangeCursor() { return changes.oldestUsableCursor(); }
}
