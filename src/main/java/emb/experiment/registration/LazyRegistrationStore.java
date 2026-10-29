package emb.experiment.registration;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Registration store that postpones sorting rank values until a rank is read.
 */
public final class LazyRegistrationStore implements RegistrationStore {
    private static final class Row {
        final long sequence;
        final int registrationId;
        final long passageMillis;
        long elapsedMillis;
        boolean valid = true;

        Row(long sequence, int registrationId, long passageMillis, long elapsedMillis) {
            this.sequence = sequence;
            this.registrationId = registrationId;
            this.passageMillis = passageMillis;
            this.elapsedMillis = elapsedMillis;
        }
    }

    private Row[] rows;
    private int size;
    private int rowGrowthEvents;
    private boolean rebuilding;

    private long[] rankValues;
    private long[] sortedRankValues;
    private int rankableCount;
    private int rankGrowthEvents;
    private boolean rankingDirty = true;

    private final Map<Integer, Long> stageStarts = new HashMap<Integer, Long>(1024);
    private final ChangeJournal changes;

    private long stateRevision;
    private long rankingRevision;
    private long lastMutationSequence;
    private long lastRevokeSequence;

    public LazyRegistrationStore(int initialCapacity) {
        this(initialCapacity, 256);
    }

    public LazyRegistrationStore(int initialCapacity, int changeHistoryCapacity) {
        if (initialCapacity < 1)
            throw new IllegalArgumentException("initialCapacity must be positive");

        rows = new Row[initialCapacity];
        rankValues = new long[initialCapacity];
        sortedRankValues = new long[initialCapacity];
        changes = new ChangeJournal(changeHistoryCapacity);
    }

    @Override
    public void beginRebuild() {
        if (size != 0 || rebuilding)
            throw new IllegalStateException("rebuild requires a new empty store");
        rebuilding = true;
    }

    @Override
    public void completeRebuild() {
        if (!rebuilding)
            throw new IllegalStateException("rebuild was not started");

        rebuilding = false;
        changes.resetToRevision(stateRevision);
    }

    @Override
    public boolean add(long sequence, int registrationId, long passageMillis) {
        validateAdd(sequence, passageMillis);
        ensureRowCapacity();

        long elapsedMillis = elapsed(registrationId, passageMillis);
        rows[size++] = new Row(sequence, registrationId, passageMillis, elapsedMillis);

        boolean rankingChanged = elapsedMillis != MISSING_TIME;
        if (rankingChanged) {
            addRankValue(elapsedMillis);
            rankingRevision++;
        }

        lastMutationSequence = sequence;
        stateRevision++;

        if (!rebuilding) {
            changes.record(
                    stateRevision,
                    ChangeJournal.Kind.ADDED,
                    registrationId,
                    sequence,
                    sequence,
                    rankingChanged);
        }
        return true;
    }

    @Override
    public boolean revoke(long sequence, int registrationId, long passageMillis) {
        if (sequence <= lastMutationSequence)
            throw new IllegalArgumentException("REV sequence must increase");

        for (int i = size - 1; i >= 0; i--) {
            Row row = rows[i];
            if (row.valid
                    && row.registrationId == registrationId
                    && row.passageMillis == passageMillis) {

                row.valid = false;
                boolean rankingChanged = row.elapsedMillis != MISSING_TIME;

                if (rankingChanged) {
                    removeRankValue(row.elapsedMillis);
                    rankingRevision++;
                }

                lastMutationSequence = sequence;
                lastRevokeSequence = sequence;
                stateRevision++;

                if (!rebuilding) {
                    changes.record(
                            stateRevision,
                            ChangeJournal.Kind.REVOKED,
                            registrationId,
                            sequence,
                            row.sequence,
                            rankingChanged);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean setStart(int registrationId, long startEpochMillis) {
        if (startEpochMillis < 0)
            throw new IllegalArgumentException("start must be UTC epoch milliseconds");

        Long previous = stageStarts.put(registrationId, startEpochMillis);
        if (previous != null && previous.longValue() == startEpochMillis)
            return false;

        boolean rankingChanged = updateRowsForStart(registrationId);
        stateRevision++;

        if (!rebuilding) {
            changes.record(
                    stateRevision,
                    ChangeJournal.Kind.START_SET,
                    registrationId,
                    0L,
                    0L,
                    rankingChanged);
        }
        return true;
    }

    @Override
    public boolean deleteStart(int registrationId) {
        if (stageStarts.remove(registrationId) == null)
            return false;

        boolean rankingChanged = updateRowsForStart(registrationId);
        stateRevision++;

        if (!rebuilding) {
            changes.record(
                    stateRevision,
                    ChangeJournal.Kind.START_DELETED,
                    registrationId,
                    0L,
                    0L,
                    rankingChanged);
        }
        return true;
    }

    @Override
    public int visitAfter(long afterSequence, int limit, Visitor visitor) {
        validateVisit(afterSequence, limit, visitor);

        if (rebuilding)
            throw new IllegalStateException("historical replay is not complete");

        int first = firstRowAfter(afterSequence);
        int visited = 0;

        for (int i = first; i < size && visited < limit; i++) {
            Row row = rows[i];
            int rank = row.valid && row.elapsedMillis != MISSING_TIME
                    ? rankOf(row.elapsedMillis)
                    : 0;

            visitor.accept(
                    row.sequence,
                    row.registrationId,
                    row.passageMillis,
                    row.elapsedMillis,
                    row.valid,
                    rank);
            visited++;
        }
        return visited;
    }

    @Override
    public int visitChangesAfter(long revision, int limit, ChangeJournal.Visitor visitor) {
        return changes.visitAfter(revision, limit, visitor);
    }

    private void validateAdd(long sequence, long passageMillis) {
        if (sequence <= lastMutationSequence || passageMillis < 0)
            throw new IllegalArgumentException(
                    "ADD sequence must increase and passage time must be nonnegative");
    }

    private static void validateVisit(long afterSequence, int limit, Visitor visitor) {
        if (afterSequence < 0 || limit < 0 || visitor == null)
            throw new IllegalArgumentException("invalid registration query");
    }

    private void ensureRowCapacity() {
        if (size < rows.length)
            return;

        rows = Arrays.copyOf(rows, Math.max(size + 1, rows.length * 2));
        rowGrowthEvents++;
    }

    private void ensureRankCapacity() {
        if (rankableCount < rankValues.length)
            return;

        int next = Math.max(rankableCount + 1, rankValues.length * 2);
        rankValues = Arrays.copyOf(rankValues, next);
        sortedRankValues = Arrays.copyOf(sortedRankValues, next);
        rankGrowthEvents++;
    }

    private void addRankValue(long elapsedMillis) {
        ensureRankCapacity();
        rankValues[rankableCount++] = elapsedMillis;
        rankingDirty = true;
    }

    private void removeRankValue(long elapsedMillis) {
        for (int i = 0; i < rankableCount; i++) {
            if (rankValues[i] == elapsedMillis) {
                rankValues[i] = rankValues[--rankableCount];
                rankingDirty = true;
                return;
            }
        }

        throw new IllegalStateException("rank value not found: " + elapsedMillis);
    }

    private int rankOf(long elapsedMillis) {
        ensureRankingSorted();
        return lowerBound(sortedRankValues, rankableCount, elapsedMillis) + 1;
    }

    private void ensureRankingSorted() {
        if (!rankingDirty)
            return;

        if (sortedRankValues.length < rankableCount)
            sortedRankValues = new long[rankValues.length];

        System.arraycopy(rankValues, 0, sortedRankValues, 0, rankableCount);
        Arrays.sort(sortedRankValues, 0, rankableCount);
        rankingDirty = false;
    }

    private boolean updateRowsForStart(int registrationId) {
        boolean rankingChanged = false;

        for (int i = 0; i < size; i++) {
            Row row = rows[i];
            if (row.registrationId != registrationId)
                continue;

            long nextElapsed = elapsed(registrationId, row.passageMillis);
            if (nextElapsed == row.elapsedMillis)
                continue;

            if (row.valid && row.elapsedMillis != MISSING_TIME) {
                removeRankValue(row.elapsedMillis);
                rankingChanged = true;
            }

            row.elapsedMillis = nextElapsed;

            if (row.valid && nextElapsed != MISSING_TIME) {
                addRankValue(nextElapsed);
                rankingChanged = true;
            }
        }

        if (rankingChanged)
            rankingRevision++;

        return rankingChanged;
    }

    private long elapsed(int registrationId, long passageMillis) {
        Long start = stageStarts.get(registrationId);

        if (start == null || passageMillis < start.longValue())
            return MISSING_TIME;

        return passageMillis - start.longValue();
    }

    private int firstRowAfter(long sequence) {
        int low = 0;
        int high = size;

        while (low < high) {
            int middle = (low + high) >>> 1;
            if (rows[middle].sequence <= sequence)
                low = middle + 1;
            else
                high = middle;
        }
        return low;
    }

    private static int lowerBound(long[] values, int length, long key) {
        int low = 0;
        int high = length;

        while (low < high) {
            int middle = (low + high) >>> 1;
            if (values[middle] < key)
                low = middle + 1;
            else
                high = middle;
        }
        return low;
    }

    @Override public int size() { return size; }
    @Override public int rankableCount() { return rankableCount; }
    @Override public int rowCapacity() { return rows.length; }
    @Override public int rowGrowthEvents() { return rowGrowthEvents; }
    @Override public int rankGrowthEvents() { return rankGrowthEvents; }
    @Override public long stateRevision() { return stateRevision; }
    @Override public long rankingRevision() { return rankingRevision; }
    @Override public long lastMutationSequence() { return lastMutationSequence; }
    @Override public long lastRevokeSequence() { return lastRevokeSequence; }
    @Override public long oldestUsableChangeCursor() { return changes.oldestUsableCursor(); }
}
