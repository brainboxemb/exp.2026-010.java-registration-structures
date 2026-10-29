package emb.experiment.active;

/**
 * Fixed-size primitive mutation ring for display synchronization.
 *
 * The mutation is a current-state marker, not a second historical record.
 * Subject means stable row index for row mutations and packed registrationId
 * for START_CHANGED.
 */
public final class RegistrationMutations {
    public enum Kind {
        ROW_ADDED,
        ROW_REVOKED,
        START_CHANGED
    }

    public interface Visitor {
        void accept(long revision, Kind kind, int subject, int flags);
    }

    public static final int RANKING_CHANGED = 1;

    private static final Kind[] KINDS = Kind.values();

    private final byte[] kinds;
    private final int[] subjects;
    private final byte[] flags;
    private long latestRevision;

    public RegistrationMutations(int capacity) {
        if (capacity < 1)
            throw new IllegalArgumentException("mutation capacity must be positive");

        kinds = new byte[capacity];
        subjects = new int[capacity];
        flags = new byte[capacity];
    }

    long append(Kind kind, int subject, int mutationFlags) {
        if (kind == null)
            throw new IllegalArgumentException("kind must not be null");
        if ((mutationFlags & ~0xff) != 0)
            throw new IllegalArgumentException("flags must fit in one byte");

        long revision = ++latestRevision;
        int slot = slot(revision);

        kinds[slot] = (byte) kind.ordinal();
        subjects[slot] = subject;
        flags[slot] = (byte) mutationFlags;

        return revision;
    }

    public long latestRevision() {
        return latestRevision;
    }

    /**
     * Earliest retained mutation revision. Returns latestRevision + 1 when empty.
     */
    public long oldestAvailableRevision() {
        if (latestRevision == 0)
            return 1;

        return Math.max(1L, latestRevision - kinds.length + 1L);
    }

    /**
     * Earliest cursor from which every later mutation is still available.
     */
    public long oldestUsableCursor() {
        return Math.max(0L, latestRevision - kinds.length);
    }

    public int capacity() {
        return kinds.length;
    }

    public int visitAfter(long afterRevision, int limit, Visitor visitor) {
        if (afterRevision < 0 || afterRevision > latestRevision || limit < 0 || visitor == null)
            throw new IllegalArgumentException("invalid mutation query");

        long oldestCursor = oldestUsableCursor();
        if (afterRevision < oldestCursor)
            throw new HistoryExpiredException(afterRevision, oldestCursor);

        int visited = 0;
        for (long revision = afterRevision + 1;
             revision <= latestRevision && visited < limit;
             revision++) {

            int slot = slot(revision);
            visitor.accept(
                    revision,
                    KINDS[kinds[slot]],
                    subjects[slot],
                    flags[slot] & 0xff);
            visited++;
        }
        return visited;
    }

    private int slot(long revision) {
        return (int) ((revision - 1L) % kinds.length);
    }

    public static final class HistoryExpiredException extends IllegalStateException {
        private final long oldestUsableCursor;

        HistoryExpiredException(long requestedCursor, long oldestUsableCursor) {
            super("mutation history expired: cursor " + requestedCursor
                    + " is older than " + oldestUsableCursor);
            this.oldestUsableCursor = oldestUsableCursor;
        }

        public long oldestUsableCursor() {
            return oldestUsableCursor;
        }
    }
}
