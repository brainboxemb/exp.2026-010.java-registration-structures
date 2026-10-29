package emb.experiment.registration;

/**
 * A bounded, preallocated mutation journal for display/update consumers.
 * All storage is primitive; append has no per-entry object allocation.
 *
 * Consumers that fall behind the retained revision window must resynchronize
 * using a complete display page/list snapshot.
 */
public final class ChangeJournal {
    public enum Kind { ADDED, REVOKED, START_SET, START_DELETED }

    public interface Visitor {
        void accept(long revision, Kind kind, int registrationId,
                    long mutationSequence, long affectedAddSequence, boolean rankingInvalidated);
    }

    private static final Kind[] KINDS = Kind.values();

    private final long[] mutationSequences;
    private final long[] affectedSequences;
    private final int[] registrationIds;
    private final byte[] kinds;
    private final boolean[] rankingInvalidated;
    private long latestRevision;
    private long resetFloor;

    public ChangeJournal(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        mutationSequences = new long[capacity];
        affectedSequences = new long[capacity];
        registrationIds = new int[capacity];
        kinds = new byte[capacity];
        rankingInvalidated = new boolean[capacity];
    }

    /** Append one new consecutive revision; no collection or entry allocation. */
    void record(long revision, Kind kind, int id, long mutationSequence,
                long affectedAddSequence, boolean invalidatesRanking) {
        if (kind == null || revision != latestRevision + 1)
            throw new IllegalArgumentException("revision must increment and kind must be set");
        int at = (int) ((revision - 1) % kinds.length);
        mutationSequences[at] = mutationSequence;
        affectedSequences[at] = affectedAddSequence;
        registrationIds[at] = id;
        kinds[at] = (byte) kind.ordinal();
        rankingInvalidated[at] = invalidatesRanking;
        latestRevision = revision;
    }

    /**
     * Starts a new silent-rebuild epoch: all earlier events become unreadable.
     * An initial full snapshot should carry this revision as its change cursor.
     */
    void resetToRevision(long revision) {
        if (revision < latestRevision)
            throw new IllegalArgumentException("reset cannot move revision backwards");
        latestRevision = revision;
        resetFloor = revision;
    }

    /** Earliest cursor that can receive every change after it. */
    public long oldestUsableCursor() {
        return Math.max(resetFloor, latestRevision - kinds.length);
    }

    /** Latest revision; caller may store it alongside a fully synchronized view. */
    public long latestRevision() { return latestRevision; }

    /**
     * Visit at most limit events with revision > afterRevision in ascending order.
     * Throws if there has been an overflow instead of silently returning an
     * incomplete change stream. Caller should then perform a full resynchronization.
     */
    public int visitAfter(long afterRevision, int limit, Visitor visitor) {
        if (afterRevision < 0L || afterRevision > latestRevision || limit < 0 || visitor == null)
            throw new IllegalArgumentException("invalid journal query");
        if (afterRevision < oldestUsableCursor())
            throw new HistoryExpiredException(afterRevision, oldestUsableCursor());

        int count = 0;
        for (long revision = afterRevision + 1; revision <= latestRevision && count < limit; revision++) {
            int at = (int) ((revision - 1) % kinds.length);
            visitor.accept(revision, KINDS[kinds[at]], registrationIds[at],
                    mutationSequences[at], affectedSequences[at], rankingInvalidated[at]);
            count++;
        }
        return count;
    }

    public static final class HistoryExpiredException extends IllegalStateException {
        private final long oldestUsableCursor;

        HistoryExpiredException(long requested, long oldestUsableCursor) {
            super("change history expired: cursor " + requested
                    + " is older than " + oldestUsableCursor);
            this.oldestUsableCursor = oldestUsableCursor;
        }

        public long oldestUsableCursor() { return oldestUsableCursor; }
    }
}
