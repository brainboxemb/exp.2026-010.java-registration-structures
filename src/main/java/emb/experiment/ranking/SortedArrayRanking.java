package emb.experiment.ranking;

/**
 * Candidate 1: keep all elapsed times in one sorted primitive array.
 *
 * Rank lookup is O(log N). Mutations use binary search plus array shifts.
 * Around ~500 registrations this simple solution may be very competitive.
 */
public final class SortedArrayRanking implements RankingIndex {
    private final SortedLongList values;

    public SortedArrayRanking(int initialCapacity) {
        values = new SortedLongList(initialCapacity);
    }

    @Override public void add(long elapsedMillis) {
        requireElapsed(elapsedMillis);
        values.add(elapsedMillis);
    }

    @Override public boolean remove(long elapsedMillis) {
        requireElapsed(elapsedMillis);
        return values.removeOne(elapsedMillis);
    }

    @Override public void prepare() {
        // Always ready.
    }

    @Override public int rankOf(long elapsedMillis) {
        return values.rankOf(elapsedMillis);
    }

    @Override public int size() { return values.size(); }
    @Override public int distinctSize() { return values.distinctSize(); }
    @Override public int capacity() { return values.capacity(); }
    @Override public int growthEvents() { return values.growthEvents(); }

    private static void requireElapsed(long value) {
        if (value < 0)
            throw new IllegalArgumentException("elapsedMillis must be nonnegative");
    }
}
