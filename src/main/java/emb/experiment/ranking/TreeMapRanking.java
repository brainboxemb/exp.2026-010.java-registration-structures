package emb.experiment.ranking;

import java.util.Map;
import java.util.TreeMap;

/**
 * Candidate 2: Java TreeMap from elapsed time to occurrence count.
 *
 * Mutations are O(log distinct-values). Standard TreeMap does not keep subtree
 * occurrence counts, so rankOf() sums counts in the head map.
 *
 * This deliberately exposes the convenience/boxing trade-off of a standard JDK collection.
 */
public final class TreeMapRanking implements RankingIndex {
    private final TreeMap<Long, Integer> counts = new TreeMap<Long, Integer>();
    private int size;

    @Override
    public void add(long elapsedMillis) {
        requireElapsed(elapsedMillis);
        Integer old = counts.get(elapsedMillis);
        counts.put(elapsedMillis, old == null ? 1 : old + 1);
        size++;
    }

    @Override
    public boolean remove(long elapsedMillis) {
        requireElapsed(elapsedMillis);
        Integer old = counts.get(elapsedMillis);
        if (old == null)
            return false;

        if (old == 1)
            counts.remove(elapsedMillis);
        else
            counts.put(elapsedMillis, old - 1);

        size--;
        return true;
    }

    @Override public void prepare() {
        // Always ready.
    }

    @Override
    public int rankOf(long elapsedMillis) {
        if (!counts.containsKey(elapsedMillis))
            return 0;

        int faster = 0;
        for (Map.Entry<Long, Integer> entry : counts.headMap(elapsedMillis, false).entrySet())
            faster += entry.getValue();

        return faster + 1;
    }

    @Override public int size() { return size; }
    @Override public int distinctSize() { return counts.size(); }
    @Override public int capacity() { return -1; }
    @Override public int growthEvents() { return -1; }

    private static void requireElapsed(long value) {
        if (value < 0)
            throw new IllegalArgumentException("elapsedMillis must be nonnegative");
    }
}
