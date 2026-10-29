package emb.experiment.ranking;

/**
 * Ranking index used by ActiveRegistrations.
 *
 * Stores only elapsed times that currently participate in ranking.
 * Competition rank is 1 + the number of stored elapsed times that are strictly smaller.
 */
public interface RankingIndex {
    void add(long elapsedMillis);

    /** Removes one occurrence, or returns false when the value is absent. */
    boolean remove(long elapsedMillis);

    /**
     * Finishes any work deliberately deferred after mutations.
     * Rebuild+sort uses this; always-ready indexes do nothing.
     */
    void prepare();

    /** Returns competition rank, or zero when the value is absent. */
    int rankOf(long elapsedMillis);

    int size();
    int distinctSize();

    /** Internal preallocated capacity when meaningful, otherwise -1. */
    int capacity();

    /** Number of internal capacity growth events when meaningful, otherwise -1. */
    int growthEvents();
}
