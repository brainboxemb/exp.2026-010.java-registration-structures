package emb.experiment.ranking;

import java.util.Arrays;

/**
 * Baseline: retain elapsed times unsorted and rebuild a sorted scratch array
 * when prepare() is called after mutations.
 *
 * This represents the essential cost pattern of the current full rebuild+sort
 * approach without pulling the rest of the Timing Point application into the experiment.
 */
public final class RebuildSortRanking implements RankingIndex {
    private final LongArrayList values;
    private long[] sorted;
    private boolean dirty = true;

    public RebuildSortRanking(int initialCapacity) {
        values = new LongArrayList(initialCapacity);
        sorted = new long[initialCapacity];
    }

    @Override
    public void add(long elapsedMillis) {
        requireElapsed(elapsedMillis);
        values.add(elapsedMillis);
        dirty = true;
    }

    @Override
    public boolean remove(long elapsedMillis) {
        requireElapsed(elapsedMillis);
        boolean removed = values.removeOneUnordered(elapsedMillis);
        dirty |= removed;
        return removed;
    }

    @Override
    public void prepare() {
        if (!dirty)
            return;

        if (sorted.length < values.size())
            sorted = new long[values.capacity()];

        values.copyTo(sorted);
        Arrays.sort(sorted, 0, values.size());
        dirty = false;
    }

    @Override
    public int rankOf(long elapsedMillis) {
        prepare();
        int at = lowerBound(sorted, values.size(), elapsedMillis);
        return at < values.size() && sorted[at] == elapsedMillis ? at + 1 : 0;
    }

    @Override public int size() { return values.size(); }

    @Override
    public int distinctSize() {
        prepare();
        if (values.size() == 0)
            return 0;

        int distinct = 1;
        for (int i = 1; i < values.size(); i++) {
            if (sorted[i] != sorted[i - 1])
                distinct++;
        }
        return distinct;
    }

    @Override public int capacity() { return values.capacity(); }
    @Override public int growthEvents() { return values.growthEvents(); }

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

    private static void requireElapsed(long value) {
        if (value < 0)
            throw new IllegalArgumentException("elapsedMillis must be nonnegative");
    }
}
