package emb.experiment.registration;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Standalone, Java 8-compatible experiment model; not a production replacement.
 * All times in this slice are UTC epoch milliseconds. Protocol timestamp resolution
 * (time-only starts, midnight rollover, DST) remains owned by production StageTiming.
 * Not thread safe: mutations are serialized, like the Node task lane.
 */
public final class RegistrationProjection {
    public enum RankingMode { REBUILD, SORTED_ARRAY }

    public interface RowVisitor {
        void accept(long sequence, int registrationId, long passageMillis,
                    long elapsedMillis, boolean valid, int competitionRank);
    }

    public static final long MISSING = -1L;

    private static final class Row {
        final long sequence;
        final int id;
        final long passage;
        long elapsed;
        boolean valid = true;

        Row(long sequence, int id, long passage, long elapsed) {
            this.sequence = sequence;
            this.id = id;
            this.passage = passage;
            this.elapsed = elapsed;
        }
    }

    private interface Ranking {
        void add(long elapsed);
        void remove(long elapsed);
        int rank(long elapsed);
        int size();
        int growthEvents();
    }

    private abstract static class ArrayRanking implements Ranking {
        long[] values;
        int count;
        int growthEvents;

        ArrayRanking(int capacity) {
            values = new long[capacity];
        }

        final void ensureCapacity() {
            if (count < values.length) return;
            values = Arrays.copyOf(values, Math.max(1, values.length * 2));
            growthEvents++;
        }

        @Override public int size() { return count; }
        @Override public int growthEvents() { return growthEvents; }

        static int lowerBound(long[] values, int count, long key) {
            int low = 0, high = count;
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (values[mid] < key) low = mid + 1;
                else high = mid;
            }
            return low;
        }
    }

    /** Comparison candidate: primitive rebuild+sort on first query after mutations. */
    private static final class RebuildRanking extends ArrayRanking {
        long[] sorted;
        boolean dirty = true;

        RebuildRanking(int capacity) {
            super(capacity);
            sorted = new long[capacity];
        }

        @Override public void add(long elapsed) {
            ensureCapacity();
            values[count++] = elapsed;
            dirty = true;
        }

        @Override public void remove(long elapsed) {
            for (int i = 0; i < count; i++) {
                if (values[i] == elapsed) {
                    values[i] = values[--count];
                    dirty = true;
                    return;
                }
            }
            throw new IllegalStateException("rank value not found: " + elapsed);
        }

        @Override public int rank(long elapsed) {
            if (dirty) {
                if (sorted.length < count) sorted = new long[values.length];
                System.arraycopy(values, 0, sorted, 0, count);
                Arrays.sort(sorted, 0, count);
                dirty = false;
            }
            return lowerBound(sorted, count, elapsed) + 1;
        }
    }

    /** Comparison candidate: sorted primitive array, including equal elapsed values. */
    private static final class SortedArrayRanking extends ArrayRanking {
        SortedArrayRanking(int capacity) { super(capacity); }

        @Override public void add(long elapsed) {
            ensureCapacity();
            int at = lowerBound(values, count, elapsed);
            System.arraycopy(values, at, values, at + 1, count - at);
            values[at] = elapsed;
            count++;
        }

        @Override public void remove(long elapsed) {
            int at = lowerBound(values, count, elapsed);
            if (at == count || values[at] != elapsed)
                throw new IllegalStateException("rank value not found: " + elapsed);
            System.arraycopy(values, at + 1, values, at, count - at - 1);
            count--;
        }

        @Override public int rank(long elapsed) {
            return lowerBound(values, count, elapsed) + 1;
        }
    }

    private Row[] rows;
    private int size;
    private int rowGrowthEvents;
    private final Ranking ranking;
    private final ChangeJournal changes;
    private final Map<Integer, Long> stageStarts = new HashMap<Integer, Long>(1024);
    private long stateRevision;
    private long rankingRevision;
    private long lastMutationSequence;
    private long lastRevokeSequence;

    public RegistrationProjection(RankingMode mode, int initialCapacity) {
        this(mode, initialCapacity, 256);
    }

    public RegistrationProjection(RankingMode mode, int initialCapacity, int changeHistoryCapacity) {
        if (mode == null || initialCapacity < 1)
            throw new IllegalArgumentException("mode and positive initialCapacity required");
        changes = new ChangeJournal(changeHistoryCapacity);
        rows = new Row[initialCapacity];
        ranking = mode == RankingMode.REBUILD
                ? new RebuildRanking(initialCapacity)
                : new SortedArrayRanking(initialCapacity);
    }

    public boolean add(long sequence, int id, long passageMillis) {
        if (sequence <= lastMutationSequence || passageMillis < 0)
            throw new IllegalArgumentException("ADD sequence must increase and passage must be UTC epoch ms");
        if (size == rows.length) {
            rows = Arrays.copyOf(rows, rows.length * 2);
            rowGrowthEvents++;
        }
        long elapsed = elapsed(id, passageMillis);
        rows[size++] = new Row(sequence, id, passageMillis, elapsed);
        boolean invalidatesRanking = elapsed != MISSING;
        if (invalidatesRanking) {
            ranking.add(elapsed);
            rankingRevision++;
        }
        lastMutationSequence = sequence;
        stateRevision++;
        changes.record(stateRevision, ChangeJournal.Kind.ADDED, id,
                sequence, sequence, invalidatesRanking);
        return true;
    }

    /** REV invalidates the newest still-valid identical (ID, passage timestamp) ADD. */
    public boolean revoke(long sequence, int id, long passageMillis) {
        if (sequence <= lastMutationSequence)
            throw new IllegalArgumentException("REV sequence must increase");
        for (int i = size - 1; i >= 0; i--) {
            Row row = rows[i];
            if (row.valid && row.id == id && row.passage == passageMillis) {
                row.valid = false;
                boolean invalidatesRanking = row.elapsed != MISSING;
                if (invalidatesRanking) {
                    ranking.remove(row.elapsed);
                    rankingRevision++;
                }
                lastMutationSequence = sequence;
                lastRevokeSequence = sequence;
                stateRevision++;
                changes.record(stateRevision, ChangeJournal.Kind.REVOKED, id,
                        sequence, row.sequence, invalidatesRanking);
                return true;
            }
        }
        return false;
    }

    public boolean setStart(int id, long startEpochMillis) {
        if (startEpochMillis < 0) throw new IllegalArgumentException("start must be UTC epoch ms");
        Long previous = stageStarts.put(id, startEpochMillis);
        if (previous != null && previous.longValue() == startEpochMillis) return false;
        boolean invalidatesRanking = updateRowsForStart(id);
        stateRevision++;
        changes.record(stateRevision, ChangeJournal.Kind.START_SET, id,
                0L, 0L, invalidatesRanking);
        return true;
    }

    public boolean deleteStart(int id) {
        if (stageStarts.remove(id) == null) return false;
        boolean invalidatesRanking = updateRowsForStart(id);
        stateRevision++;
        changes.record(stateRevision, ChangeJournal.Kind.START_DELETED, id,
                0L, 0L, invalidatesRanking);
        return true;
    }

    private boolean updateRowsForStart(int id) {
        boolean rankingChanged = false;
        for (int i = 0; i < size; i++) {
            Row row = rows[i];
            if (row.id != id) continue;
            long next = elapsed(id, row.passage);
            if (row.elapsed == next) continue;
            if (row.valid && row.elapsed != MISSING) {
                ranking.remove(row.elapsed);
                rankingChanged = true;
            }
            row.elapsed = next;
            if (row.valid && next != MISSING) {
                ranking.add(next);
                rankingChanged = true;
            }
        }
        if (rankingChanged) rankingRevision++;
        return rankingChanged;
    }

    private long elapsed(int id, long passageMillis) {
        Long start = stageStarts.get(id);
        return start != null && passageMillis >= start.longValue()
                ? passageMillis - start.longValue() : MISSING;
    }

    /** Chronological ADD order; invalid rows remain visible. Limit is enforced during visiting. */
    public int visitAfter(long afterSequence, int limit, RowVisitor visitor) {
        if (afterSequence < 0 || limit < 0 || visitor == null)
            throw new IllegalArgumentException("invalid display query");
        // Accepted ADD sequences are strictly increasing: page access is O(log N + limit).
        int low = 0;
        int high = size;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (rows[middle].sequence <= afterSequence) low = middle + 1;
            else high = middle;
        }

        int visited = 0;
        for (int i = low; i < size && visited < limit; i++) {
            Row row = rows[i];
            visitor.accept(row.sequence, row.id, row.passage, row.elapsed,
                    row.valid, row.valid && row.elapsed != MISSING ? ranking.rank(row.elapsed) : 0);
            visited++;
        }
        return visited;
    }

    /** Replay bounded display/change deltas; a stale cursor throws HistoryExpiredException. */
    public int visitChangesAfter(long revision, int limit, ChangeJournal.Visitor visitor) {
        return changes.visitAfter(revision, limit, visitor);
    }

    public long oldestUsableChangeCursor() { return changes.oldestUsableCursor(); }

    public int size() { return size; }
    public int rankableCount() { return ranking.size(); }
    public int rowCapacity() { return rows.length; }
    public int rowGrowthEvents() { return rowGrowthEvents; }
    public int rankGrowthEvents() { return ranking.growthEvents(); }
    public long stateRevision() { return stateRevision; }
    public long rankingRevision() { return rankingRevision; }
    public long lastMutationSequence() { return lastMutationSequence; }
    public long lastRevokeSequence() { return lastRevokeSequence; }
}
