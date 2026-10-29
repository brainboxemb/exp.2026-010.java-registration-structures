package emb.experiment.registration;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Primitive structure-of-arrays candidate: no per-registration Row allocation.
 * It uses the same sorted primitive elapsed-time ranking index as SORTED_ARRAY.
 *
 * Normalized UTC epoch milliseconds only; not a drop-in production replacement.
 * Mutations must be serialized by the owner.
 */
public final class PackedProjection implements ProjectionModel {
    private static final long MISSING = RegistrationProjection.MISSING;
    private final Map<Integer, Long> starts = new HashMap<Integer, Long>(1024);
    private final ChangeJournal changes;
    private long[] sequences;
    private long[] passages;
    private long[] elapsed;
    private int[] ids;
    private boolean[] valid;

    private long[] sortedElapsed;
    private int size;
    private int ranked;
    private int rowGrowth;
    private int rankGrowth;
    private long stateRevision;
    private long rankingRevision;
    private long lastMutationSequence;
    private long lastRevokeSequence;

    public PackedProjection(int initialCapacity) {
        this(initialCapacity, 256);
    }

    public PackedProjection(int initialCapacity, int historyCapacity) {
        if (initialCapacity < 1) throw new IllegalArgumentException("capacity must be positive");
        sequences = new long[initialCapacity];
        passages = new long[initialCapacity];
        elapsed = new long[initialCapacity];
        ids = new int[initialCapacity];
        valid = new boolean[initialCapacity];
        sortedElapsed = new long[initialCapacity];
        changes = new ChangeJournal(historyCapacity);
    }

    private static int lowerBound(long[] a, int count, long value) {
        int lo = 0, hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < value) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private void ensureRowCapacity() {
        if (size < sequences.length) return;
        int next = Math.max(size + 1, sequences.length * 2);
        sequences = Arrays.copyOf(sequences, next);
        passages = Arrays.copyOf(passages, next);
        elapsed = Arrays.copyOf(elapsed, next);
        ids = Arrays.copyOf(ids, next);
        valid = Arrays.copyOf(valid, next);
        rowGrowth++;
    }

    private void insertRank(long value) {
        if (ranked == sortedElapsed.length) {
            sortedElapsed = Arrays.copyOf(sortedElapsed, Math.max(1, sortedElapsed.length * 2));
            rankGrowth++;
        }
        int at = lowerBound(sortedElapsed, ranked, value);
        System.arraycopy(sortedElapsed, at, sortedElapsed, at + 1, ranked - at);
        sortedElapsed[at] = value;
        ranked++;
    }

    private void removeRank(long value) {
        int at = lowerBound(sortedElapsed, ranked, value);
        if (at == ranked || sortedElapsed[at] != value)
            throw new IllegalStateException("rank value not retained: " + value);
        System.arraycopy(sortedElapsed, at + 1, sortedElapsed, at, ranked - at - 1);
        ranked--;
    }

    private long resolve(int id, long passage) {
        Long start = starts.get(id);
        return start == null || start.longValue() > passage
                ? MISSING : passage - start.longValue();
    }

    @Override public boolean add(long sequence, int id, long passage) {
        if (sequence <= lastMutationSequence || passage < 0)
            throw new IllegalArgumentException("ADD sequence must increase and time must be nonnegative");
        ensureRowCapacity();
        int at = size++;
        sequences[at] = sequence;
        ids[at] = id;
        passages[at] = passage;
        elapsed[at] = resolve(id, passage);
        valid[at] = true;
        boolean changedRank = elapsed[at] != MISSING;
        if (changedRank) {
            insertRank(elapsed[at]);
            rankingRevision++;
        }
        lastMutationSequence = sequence;
        stateRevision++;
        changes.record(stateRevision, ChangeJournal.Kind.ADDED, id,
                sequence, sequence, changedRank);
        return true;
    }

    @Override public boolean revoke(long sequence, int id, long passage) {
        if (sequence <= lastMutationSequence)
            throw new IllegalArgumentException("REV sequence must increase");
        for (int i = size - 1; i >= 0; i--) {
            if (valid[i] && ids[i] == id && passages[i] == passage) {
                valid[i] = false;
                boolean changedRank = elapsed[i] != MISSING;
                if (changedRank) {
                    removeRank(elapsed[i]);
                    rankingRevision++;
                }
                lastMutationSequence = sequence;
                lastRevokeSequence = sequence;
                stateRevision++;
                changes.record(stateRevision, ChangeJournal.Kind.REVOKED, id,
                        sequence, sequences[i], changedRank);
                return true;
            }
        }
        return false;
    }

    @Override public boolean setStart(int id, long startMillis) {
        if (startMillis < 0) throw new IllegalArgumentException("start must be nonnegative");
        Long previous = starts.put(id, startMillis);
        if (previous != null && previous.longValue() == startMillis) return false;
        boolean changedRank = refreshStart(id);
        stateRevision++;
        changes.record(stateRevision, ChangeJournal.Kind.START_SET, id, 0L, 0L, changedRank);
        return true;
    }

    @Override public boolean deleteStart(int id) {
        if (starts.remove(id) == null) return false;
        boolean changedRank = refreshStart(id);
        stateRevision++;
        changes.record(stateRevision, ChangeJournal.Kind.START_DELETED, id, 0L, 0L, changedRank);
        return true;
    }

    private boolean refreshStart(int id) {
        boolean changedRank = false;
        for (int i = 0; i < size; i++) {
            if (ids[i] != id) continue;
            long now = resolve(id, passages[i]);
            if (now == elapsed[i]) continue;
            if (valid[i] && elapsed[i] != MISSING) {
                removeRank(elapsed[i]);
                changedRank = true;
            }
            elapsed[i] = now;
            if (valid[i] && now != MISSING) {
                insertRank(now);
                changedRank = true;
            }
        }
        if (changedRank) rankingRevision++;
        return changedRank;
    }

    @Override public int visitAfter(long afterSequence, int limit,
                                    RegistrationProjection.RowVisitor visitor) {
        if (afterSequence < 0L || limit < 0 || visitor == null)
            throw new IllegalArgumentException("invalid display query");
        int lo = 0, hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sequences[mid] <= afterSequence) lo = mid + 1;
            else hi = mid;
        }
        int count = 0;
        for (int i = lo; i < size && count < limit; i++) {
            int rank = valid[i] && elapsed[i] != MISSING
                    ? lowerBound(sortedElapsed, ranked, elapsed[i]) + 1 : 0;
            visitor.accept(sequences[i], ids[i], passages[i], elapsed[i], valid[i], rank);
            count++;
        }
        return count;
    }

    @Override public int visitChangesAfter(long afterRevision, int limit,
                                           ChangeJournal.Visitor visitor) {
        return changes.visitAfter(afterRevision, limit, visitor);
    }

    @Override public int size() { return size; }
    @Override public int rankableCount() { return ranked; }
    @Override public int rowCapacity() { return sequences.length; }
    @Override public int rowGrowthEvents() { return rowGrowth; }
    @Override public int rankGrowthEvents() { return rankGrowth; }
    @Override public long stateRevision() { return stateRevision; }
    @Override public long rankingRevision() { return rankingRevision; }
    @Override public long lastMutationSequence() { return lastMutationSequence; }
    @Override public long lastRevokeSequence() { return lastRevokeSequence; }
    @Override public long oldestUsableChangeCursor() { return changes.oldestUsableCursor(); }
}
