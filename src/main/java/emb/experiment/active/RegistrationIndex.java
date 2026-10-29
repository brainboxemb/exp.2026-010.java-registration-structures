package emb.experiment.active;

import java.util.Arrays;

/**
 * Primitive open-addressed lookup keyed by packed registrationId.
 *
 * Entries are never deleted while one timing point is active. That keeps the
 * table simple: key 0 means EMPTY and no tombstones are required.
 */
final class RegistrationIndex {
    static final int NO_ROW = -1;

    private int[] keys;
    private int[] retainedCounts;
    private int[] validCounts;
    private int[] onlyRetainedRows;
    private int[] onlyValidRows;

    private int occupied;
    private int uniqueRetained;
    private int uniqueValid;
    private int growthEvents;

    RegistrationIndex(int requestedCapacity) {
        if (requestedCapacity < 2)
            throw new IllegalArgumentException("index capacity must be at least 2");

        int capacity = nextPowerOfTwo(requestedCapacity);
        keys = new int[capacity];
        retainedCounts = new int[capacity];
        validCounts = new int[capacity];
        onlyRetainedRows = new int[capacity];
        onlyValidRows = new int[capacity];

        Arrays.fill(onlyRetainedRows, NO_ROW);
        Arrays.fill(onlyRetainedRows, NO_ROW);
        Arrays.fill(onlyValidRows, NO_ROW);
    }

    int ensureSlot(int registrationId) {
        requireRegistrationId(registrationId);

        int slot = findSlot(registrationId);
        if (keys[slot] != 0)
            return slot;

        // Grow only when a genuinely new id would cross the 0.5 load factor.
        if ((occupied + 1) * 2 > keys.length) {
            grow();
            slot = findSlot(registrationId);
        }

        keys[slot] = registrationId;
        occupied++;
        return slot;
    }

    int findExistingSlot(int registrationId) {
        requireRegistrationId(registrationId);

        int mask = keys.length - 1;
        int slot = hash(registrationId) & mask;

        while (true) {
            int key = keys[slot];
            if (key == 0)
                return -1;
            if (key == registrationId)
                return slot;
            slot = (slot + 1) & mask;
        }
    }

    void rowAdded(int slot, int row) {
        int retained = ++retainedCounts[slot];
        if (retained == 1) {
            uniqueRetained++;
            onlyRetainedRows[slot] = row;
        } else {
            onlyRetainedRows[slot] = NO_ROW;
        }

        int valid = ++validCounts[slot];
        if (valid == 1) {
            uniqueValid++;
            onlyValidRows[slot] = row;
        } else {
            onlyValidRows[slot] = NO_ROW;
        }
    }

    int rowRevoked(int slot) {
        int valid = validCounts[slot];
        if (valid < 1)
            throw new IllegalStateException("cannot revoke from empty registration index");

        valid--;
        validCounts[slot] = valid;

        if (valid == 0) {
            uniqueValid--;
            onlyValidRows[slot] = NO_ROW;
        } else if (valid == 1) {
            // Caller finds the one remaining row and restores the fast path.
            onlyValidRows[slot] = NO_ROW;
        } else {
            onlyValidRows[slot] = NO_ROW;
        }

        return valid;
    }

    void setOnlyValidRow(int slot, int row) {
        if (validCounts[slot] != 1)
            throw new IllegalStateException("onlyValidRow requires validCount == 1");
        onlyValidRows[slot] = row;
    }

    int retainedCount(int slot) {
        return retainedCounts[slot];
    }

    int validCount(int slot) {
        return validCounts[slot];
    }

    int onlyRetainedRow(int slot) {
        return onlyRetainedRows[slot];
    }

    int onlyValidRow(int slot) {
        return onlyValidRows[slot];
    }

    int uniqueRetainedCount() {
        return uniqueRetained;
    }

    int uniqueValidCount() {
        return uniqueValid;
    }

    int capacity() {
        return keys.length;
    }

    int growthEvents() {
        return growthEvents;
    }

    private int findSlot(int registrationId) {
        int mask = keys.length - 1;
        int slot = hash(registrationId) & mask;

        while (keys[slot] != 0 && keys[slot] != registrationId)
            slot = (slot + 1) & mask;

        return slot;
    }

    private void grow() {
        int[] oldKeys = keys;
        int[] oldRetained = retainedCounts;
        int[] oldValid = validCounts;
        int[] oldOnlyRetained = onlyRetainedRows;
        int[] oldOnlyValid = onlyValidRows;

        int newCapacity = oldKeys.length * 2;
        keys = new int[newCapacity];
        retainedCounts = new int[newCapacity];
        validCounts = new int[newCapacity];
        onlyRetainedRows = new int[newCapacity];
        onlyValidRows = new int[newCapacity];

        Arrays.fill(onlyValidRows, NO_ROW);

        occupied = 0;

        for (int i = 0; i < oldKeys.length; i++) {
            int key = oldKeys[i];
            if (key == 0)
                continue;

            int slot = findSlot(key);
            keys[slot] = key;
            retainedCounts[slot] = oldRetained[i];
            validCounts[slot] = oldValid[i];
            onlyRetainedRows[slot] = oldOnlyRetained[i];
            onlyValidRows[slot] = oldOnlyValid[i];
            occupied++;
        }

        growthEvents++;
    }

    private static int hash(int value) {
        int x = value;
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }

    private static int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value) {
            if (result > (1 << 29))
                throw new IllegalArgumentException("capacity too large");
            result <<= 1;
        }
        return result;
    }

    private static void requireRegistrationId(int registrationId) {
        if (registrationId == 0)
            throw new IllegalArgumentException("registrationId 0 is reserved as EMPTY");
    }
}
