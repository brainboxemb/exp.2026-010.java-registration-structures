package emb.experiment.active;

import emb.experiment.ranking.RankingIndex;

import java.util.Arrays;

/**
 * Allocation-conscious active registration state for one timing point.
 *
 * Rows are append-only and stored in primitive parallel arrays. REV only clears
 * VALID; stable row positions are therefore safe mutation/snapshot subjects.
 *
 * Passage times are absolute UTC epoch milliseconds. Cached start times use
 * the production compact UTC representation (yyyyMMddHHmmsscc or HHmmsscc).
 */
public final class RegistrationList {
    public interface Visitor {
        void accept(
                int row,
                long sequence,
                int registrationId,
                long passageTime,
                long startTime,
                long elapsedTime,
                boolean valid,
                int rank);
    }

    public static final long NO_TIME = Long.MIN_VALUE;

    private static final byte VALID = 1;

    private long[] sequences;
    private int[] registrationIds;
    private long[] passageTimes;
    private long[] startTimes;
    private long[] elapsedTimes;
    private byte[] flags;

    private final RegistrationIndex index;
    private final RankingIndex ranking;
    private final RegistrationMutations mutations;

    private int size;
    private int rowGrowthEvents;
    private boolean initialLoading;
    private long lastLogSequence;
    private long fastRevokeCount;
    private long scannedRevokeCount;

    public RegistrationList(
            int rowCapacity,
            int indexCapacity,
            int mutationCapacity,
            RankingIndex ranking) {

        if (rowCapacity < 1)
            throw new IllegalArgumentException("rowCapacity must be positive");
        if (ranking == null)
            throw new IllegalArgumentException("ranking must not be null");

        sequences = new long[rowCapacity];
        registrationIds = new int[rowCapacity];
        passageTimes = new long[rowCapacity];
        startTimes = new long[rowCapacity];
        elapsedTimes = new long[rowCapacity];
        flags = new byte[rowCapacity];
        Arrays.fill(startTimes, NO_TIME);
        Arrays.fill(elapsedTimes, NO_TIME);

        index = new RegistrationIndex(indexCapacity);
        mutations = new RegistrationMutations(mutationCapacity);
        this.ranking = ranking;
    }

    /**
     * Begin one silent historical load into a new RegistrationList.
     *
     * ADD/REV/start changes still build list/index/ranking state, but do not
     * create display mutations. finishInitialLoad() prepares ranking once.
     */
    public void beginInitialLoad() {
        if (initialLoading || size != 0 || lastLogSequence != 0 || mutations.latestRevision() != 0)
            throw new IllegalStateException("initial load requires a new empty RegistrationList");
        initialLoading = true;
    }

    public void finishInitialLoad() {
        if (!initialLoading)
            throw new IllegalStateException("initial load was not started");

        ranking.prepare();
        initialLoading = false;
    }

    /**
     * Append one committed ADD record. Returns the stable row index.
     */
    public int add(long logSequence, int registrationId, long passageTime) {
        requireNextLogSequence(logSequence);
        if (passageTime < 0)
            throw new IllegalArgumentException("passageTime must be nonnegative");

        ensureRowCapacity();

        int slot = index.ensureSlot(registrationId);
        long startTime = index.startTime(slot);
        long elapsedTime = elapsed(startTime, passageTime);

        int row = size++;
        sequences[row] = logSequence;
        registrationIds[row] = registrationId;
        passageTimes[row] = passageTime;
        startTimes[row] = startTime;
        elapsedTimes[row] = elapsedTime;
        flags[row] = VALID;

        index.rowAdded(slot, row);

        boolean rankingChanged = elapsedTime != NO_TIME;
        if (rankingChanged)
            ranking.add(elapsedTime);

        lastLogSequence = logSequence;
        if (!initialLoading) {
            mutations.append(
                    RegistrationMutations.Kind.ROW_ADDED,
                    row,
                    rankingChanged ? RegistrationMutations.RANKING_CHANGED : 0);
        }

        return row;
    }

    /**
     * Revoke the newest still-valid ADD with the same registrationId + passage.
     * Returns false when no such row exists.
     */
    public boolean revoke(long logSequence, int registrationId, long passageTime) {
        requireNextLogSequence(logSequence);
        lastLogSequence = logSequence;

        int slot = index.findExistingSlot(registrationId);
        if (slot < 0 || index.validCount(slot) == 0)
            return false;

        int row;

        if (index.validCount(slot) == 1) {
            row = index.onlyValidRow(slot);

            if (!matchesValidRow(row, registrationId, passageTime))
                return false;

            fastRevokeCount++;
        } else {
            row = findNewestValidRow(registrationId, passageTime);
            if (row < 0)
                return false;

            scannedRevokeCount++;
        }

        invalidateRow(slot, row);
        return true;
    }

    /**
     * Store one current start time. Returns false when it was unchanged.
     */
    public boolean setStart(int registrationId, long startTime) {
        if (startTime == NO_TIME)
            throw new IllegalArgumentException("NO_TIME is reserved");
        if (startTime < 0L)
            throw new IllegalArgumentException("compact stage start must be nonnegative");

        int slot = index.ensureSlot(registrationId);
        if (!index.setStartTime(slot, startTime))
            return false;

        boolean rankingChanged = recalculateRowsForStart(registrationId, startTime);
        if (!initialLoading && index.retainedCount(slot) > 0) {
            mutations.append(
                    RegistrationMutations.Kind.START_CHANGED,
                    registrationId,
                    rankingChanged ? RegistrationMutations.RANKING_CHANGED : 0);
        }
        return true;
    }

    /**
     * Delete one current start time. Returns false when none was known.
     */
    public boolean deleteStart(int registrationId) {
        int slot = index.findExistingSlot(registrationId);
        if (slot < 0 || !index.deleteStartTime(slot))
            return false;

        boolean rankingChanged = recalculateRowsForStart(registrationId, NO_TIME);
        if (!initialLoading && index.retainedCount(slot) > 0) {
            mutations.append(
                    RegistrationMutations.Kind.START_CHANGED,
                    registrationId,
                    rankingChanged ? RegistrationMutations.RANKING_CHANGED : 0);
        }
        return true;
    }

    /**
     * Visit a range directly from primitive storage. No result list is created.
     */
    public int visitRows(int firstRow, int limit, Visitor visitor) {
        requireReadable();
        if (firstRow < 0 || firstRow > size || limit < 0 || visitor == null)
            throw new IllegalArgumentException("invalid registration visit");

        ranking.prepare();

        int visited = 0;
        for (int row = firstRow; row < size && visited < limit; row++) {
            emitRow(row, visitor);
            visited++;
        }
        return visited;
    }

    /** Visit exactly one stable row, typically referenced by a row mutation. */
    public void visitRow(int row, Visitor visitor) {
        requireReadable();
        if (row < 0 || row >= size || visitor == null)
            throw new IllegalArgumentException("invalid registration row");

        ranking.prepare();
        emitRow(row, visitor);
    }

    /**
     * Visit all retained rows for one registrationId.
     *
     * This intentionally scans the small chronological list instead of
     * maintaining a second per-id position collection.
     */
    public int visitRegistration(int registrationId, Visitor visitor) {
        requireReadable();
        if (registrationId == 0 || visitor == null)
            throw new IllegalArgumentException("invalid registration visit");

        ranking.prepare();

        int visited = 0;
        for (int row = 0; row < size; row++) {
            if (registrationIds[row] != registrationId)
                continue;

            emitRow(row, visitor);
            visited++;
        }
        return visited;
    }

    /**
     * Visit the complete current snapshot and return its mutation marker.
     *
     * The owner must invoke this as one serialized state operation. The method
     * itself creates no snapshot list or row objects.
     */
    public long visitSnapshot(Visitor visitor) {
        requireReadable();
        if (visitor == null)
            throw new IllegalArgumentException("visitor must not be null");

        ranking.prepare();
        long revision = mutations.latestRevision();

        for (int row = 0; row < size; row++)
            emitRow(row, visitor);

        return revision;
    }

    public int visitMutationsAfter(
            long revision,
            int limit,
            RegistrationMutations.Visitor visitor) {

        requireReadable();
        return mutations.visitAfter(revision, limit, visitor);
    }

    public int size() {
        return size;
    }

    public int uniqueRetainedRegistrationCount() {
        return index.uniqueRetainedCount();
    }

    public int uniqueValidRegistrationCount() {
        return index.uniqueValidCount();
    }

    public int validCount(int registrationId) {
        int slot = index.findExistingSlot(registrationId);
        return slot < 0 ? 0 : index.validCount(slot);
    }

    public int retainedCount(int registrationId) {
        int slot = index.findExistingSlot(registrationId);
        return slot < 0 ? 0 : index.retainedCount(slot);
    }

    public long startTime(int registrationId) {
        int slot = index.findExistingSlot(registrationId);
        return slot < 0 ? NO_TIME : index.startTime(slot);
    }

    public long latestMutationRevision() {
        return mutations.latestRevision();
    }

    public long oldestAvailableMutationRevision() {
        return mutations.oldestAvailableRevision();
    }

    public int rowCapacity() {
        return sequences.length;
    }

    public int rowGrowthEvents() {
        return rowGrowthEvents;
    }

    public int indexCapacity() {
        return index.capacity();
    }

    public int indexGrowthEvents() {
        return index.growthEvents();
    }

    public int mutationCapacity() {
        return mutations.capacity();
    }

    public long fastRevokeCount() {
        return fastRevokeCount;
    }

    public long scannedRevokeCount() {
        return scannedRevokeCount;
    }

    public long lastLogSequence() {
        return lastLogSequence;
    }

    private void emitRow(int row, Visitor visitor) {
        int registrationId = registrationIds[row];
        long startTime = startTimes[row];
        long elapsedTime = elapsedTimes[row];
        boolean valid = isValid(row);

        int rank = valid && elapsedTime != NO_TIME
                ? ranking.rankOf(elapsedTime)
                : 0;

        visitor.accept(
                row,
                sequences[row],
                registrationId,
                passageTimes[row],
                startTime,
                elapsedTime,
                valid,
                rank);
    }

    private boolean recalculateRowsForStart(int registrationId, long startTime) {
        boolean rankingChanged = false;

        for (int row = 0; row < size; row++) {
            if (registrationIds[row] != registrationId)
                continue;

            long oldElapsed = elapsedTimes[row];
            long newElapsed = elapsed(startTime, passageTimes[row]);

            // Start time belongs to the row view even when elapsed remains NO_TIME.
            startTimes[row] = startTime;

            if (oldElapsed == newElapsed)
                continue;

            if (isValid(row) && oldElapsed != NO_TIME) {
                if (!ranking.remove(oldElapsed))
                    throw new IllegalStateException("RankingIndex lost elapsed value " + oldElapsed);
                rankingChanged = true;
            }

            elapsedTimes[row] = newElapsed;

            if (isValid(row) && newElapsed != NO_TIME) {
                ranking.add(newElapsed);
                rankingChanged = true;
            }
        }

        return rankingChanged;
    }

    private void invalidateRow(int lookupSlot, int row) {
        long elapsedTime = elapsedTimes[row];

        if (elapsedTime != NO_TIME && !ranking.remove(elapsedTime))
            throw new IllegalStateException("RankingIndex lost elapsed value " + elapsedTime);

        flags[row] &= ~VALID;

        int remaining = index.rowRevoked(lookupSlot);
        if (remaining == 1) {
            int remainingRow = findNewestValidRow(registrationIds[row], Long.MIN_VALUE);
            if (remainingRow < 0)
                throw new IllegalStateException("validCount == 1 but no valid row found");
            index.setOnlyValidRow(lookupSlot, remainingRow);
        }

        if (!initialLoading) {
            mutations.append(
                    RegistrationMutations.Kind.ROW_REVOKED,
                    row,
                    elapsedTime != NO_TIME ? RegistrationMutations.RANKING_CHANGED : 0);
        }
    }

    /**
     * passageTime == Long.MIN_VALUE means any passage for this registrationId.
     */
    private int findNewestValidRow(int registrationId, long passageTime) {
        for (int row = size - 1; row >= 0; row--) {
            if (registrationIds[row] != registrationId || !isValid(row))
                continue;

            if (passageTime == Long.MIN_VALUE || passageTimes[row] == passageTime)
                return row;
        }
        return -1;
    }

    private boolean matchesValidRow(int row, int registrationId, long passageTime) {
        return row >= 0
                && row < size
                && isValid(row)
                && registrationIds[row] == registrationId
                && passageTimes[row] == passageTime;
    }

    private boolean isValid(int row) {
        return (flags[row] & VALID) != 0;
    }

    private static long elapsed(long startTime, long passageTime) {
        if (startTime == NO_TIME)
            return NO_TIME;

        return StageTiming.elapsedMillisOrMissing(startTime, passageTime);
    }

    private void requireReadable() {
        if (initialLoading)
            throw new IllegalStateException("initial LogBook load is not finished");
    }

    private void requireNextLogSequence(long logSequence) {
        if (logSequence <= lastLogSequence)
            throw new IllegalArgumentException(
                    "log sequence must increase: " + logSequence + " <= " + lastLogSequence);
    }

    private void ensureRowCapacity() {
        if (size < sequences.length)
            return;

        int next = Math.max(size + 1, sequences.length * 2);

        sequences = Arrays.copyOf(sequences, next);
        registrationIds = Arrays.copyOf(registrationIds, next);
        passageTimes = Arrays.copyOf(passageTimes, next);
        startTimes = Arrays.copyOf(startTimes, next);
        elapsedTimes = Arrays.copyOf(elapsedTimes, next);
        flags = Arrays.copyOf(flags, next);

        Arrays.fill(startTimes, size, next, NO_TIME);
        Arrays.fill(elapsedTimes, size, next, NO_TIME);
        rowGrowthEvents++;
    }
}
