package emb.experiment.ranking;

import java.util.Arrays;

/**
 * Minimal sorted primitive long list.
 *
 * Binary search is O(log N); insertion/removal may shift O(N) longs.
 */
final class SortedLongList {
    private long[] values;
    private int size;
    private int growthEvents;

    SortedLongList(int initialCapacity) {
        if (initialCapacity < 1)
            throw new IllegalArgumentException("initialCapacity must be positive");
        values = new long[initialCapacity];
    }

    void add(long value) {
        ensureCapacity(size + 1);
        int at = lowerBound(value);
        System.arraycopy(values, at, values, at + 1, size - at);
        values[at] = value;
        size++;
    }

    boolean removeOne(long value) {
        int at = lowerBound(value);
        if (at == size || values[at] != value)
            return false;

        System.arraycopy(values, at + 1, values, at, size - at - 1);
        size--;
        return true;
    }

    int rankOf(long value) {
        int at = lowerBound(value);
        return at < size && values[at] == value ? at + 1 : 0;
    }

    int size() {
        return size;
    }

    int distinctSize() {
        if (size == 0)
            return 0;

        int distinct = 1;
        for (int i = 1; i < size; i++) {
            if (values[i] != values[i - 1])
                distinct++;
        }
        return distinct;
    }

    int capacity() {
        return values.length;
    }

    int growthEvents() {
        return growthEvents;
    }

    private int lowerBound(long value) {
        int low = 0;
        int high = size;

        while (low < high) {
            int middle = (low + high) >>> 1;
            if (values[middle] < value)
                low = middle + 1;
            else
                high = middle;
        }
        return low;
    }

    private void ensureCapacity(int required) {
        if (required <= values.length)
            return;

        int next = Math.max(required, values.length * 2);
        values = Arrays.copyOf(values, next);
        growthEvents++;
    }
}
