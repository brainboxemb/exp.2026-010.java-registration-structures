package emb.experiment.ranking;

import java.util.Arrays;

/**
 * Minimal primitive long list written for this experiment.
 *
 * It deliberately implements only the operations required by the ranking
 * candidates, so allocation/growth behaviour is explicit and inspectable.
 */
final class LongArrayList {
    private long[] values;
    private int size;
    private int growthEvents;

    LongArrayList(int initialCapacity) {
        if (initialCapacity < 1)
            throw new IllegalArgumentException("initialCapacity must be positive");
        values = new long[initialCapacity];
    }

    void add(long value) {
        ensureCapacity(size + 1);
        values[size++] = value;
    }

    /**
     * Removes one matching value without preserving order.
     * This is O(N), but requires no array shift.
     */
    boolean removeOneUnordered(long value) {
        for (int i = 0; i < size; i++) {
            if (values[i] == value) {
                values[i] = values[--size];
                return true;
            }
        }
        return false;
    }

    long get(int index) {
        if (index < 0 || index >= size)
            throw new IndexOutOfBoundsException("index=" + index + ", size=" + size);
        return values[index];
    }

    int size() {
        return size;
    }

    int capacity() {
        return values.length;
    }

    int growthEvents() {
        return growthEvents;
    }

    void copyTo(long[] target) {
        if (target.length < size)
            throw new IllegalArgumentException("target too small");
        System.arraycopy(values, 0, target, 0, size);
    }

    private void ensureCapacity(int required) {
        if (required <= values.length)
            return;

        int next = Math.max(required, values.length * 2);
        values = Arrays.copyOf(values, next);
        growthEvents++;
    }
}
