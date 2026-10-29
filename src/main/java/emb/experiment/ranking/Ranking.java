package emb.experiment.ranking;

/**
 * One ranking data structure under test.
 *
 * A value is an elapsed time in milliseconds. Duplicate values are allowed.
 * Competition rank is 1 + the number of stored values that are strictly smaller.
 */
public interface Ranking {
    void add(long elapsedMillis);

    /** Removes one occurrence, or returns false when the value is absent. */
    boolean remove(long elapsedMillis);

    /**
     * Finishes any work that an implementation deliberately defers after mutations.
     * Call this before rankOf() in workloads where ranking must be ready.
     */
    void prepare();

    /** Returns competition rank, or zero when the value is absent. */
    int rankOf(long elapsedMillis);

    /** Number of stored occurrences, including duplicates. */
    int size();

    /** Number of distinct elapsed-time values. */
    int distinctSize();

    /** Internal preallocated capacity when meaningful, otherwise -1. */
    int capacity();

    /** Number of internal capacity growth events when meaningful, otherwise -1. */
    int growthEvents();
}
