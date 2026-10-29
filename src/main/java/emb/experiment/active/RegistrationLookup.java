package emb.experiment.active;

import java.util.Arrays;

/**
 * Primitive open-addressed lookup keyed by packed registrationId.
 *
 * Entries are never deleted while one timing point is active. That keeps the
 * table simple: key 0 means EMPTY and no tombstones are required.
 */
final class RegistrationLookup {
    static final long NO_START = Long.MIN_VALUE;
    static final int NO_ROW = -1;

    private int[] keys;
    private int[] retainedCounts;
    private int[] validCounts;
    private int[] onlyValidRows;
    private long[] startTimes;

    private int occupied;
    private int uniqueRetained;
    private int uniqueValid;
    private int growthEvents;

    RegistrationLookup(int requestedCapacity) {
        if (requestedCapacity < 2)
            throw new IllegalArgumentException("lookup capacity must be at least 2");

        int capacity = nextPowerOfTwo(requestedCapacity);
        keys = new int[capacity];
        retainedCounts = new int[capacity];
        validCounts = new int[capacity];
        onlyValidRows = new int[capacity];
        startTimes = new long[capacity];

        Arrays.fill(onlyValidRows, NO_ROW);
        Arrays.fill(startTimes, NO_START);
    }

    int ensureSlot(int registrationId) {
        requireRegistrationId(registrationId);

        if ((occupied + 1) * 2 > keys.length)
            grow();

        int slot = findSlot(registrationId);
        if (keys[slot] == 0) {
            keys[slot] = registrationId;
            occupied++;
        }
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
        if (retainedCounts[slot]++ == 0)
            uniqueRetained++;

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
            throw new IllegalStateException("cannot revoke from empty registration lookup");

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

    long startTime(int slot) {
        return startTimes[slot];
    }

    boolean setStartTime(int slot, long startTime) {
        if (startTime == NO_START)
            throw new IllegalArgumentException("NO_START is reserved");
        if (startTimes[slot] == startTime)
            return false;

        startTimes[slot] = startTime;
        return true;
    }

    boolean deleteStartTime(int slot) {
        if (startTimes[slot] == NO_START)
            return false;

        startTimes[slot] = NO_START;
        return true;
    }

    int retainedCount(int slot) {
        return retainedCounts[slot];
    }

    int validCount(int slot) {
        return validCounts[slot];
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
        int[] oldOnly = onlyValidRows;
        long[] oldStarts = startTimes;

        int newCapacity = oldKeys.length * 2;
        keys = new int[newCapacity];
        retainedCounts = new int[newCapacity];
        validCounts = new int[newCapacity];
        onlyValidRows = new int[newCapacity];
        startTimes = new long[newCapacity];

        Arrays.fill(onlyValidRows, NO_ROW);
        Arrays.fill(startTimes, NO_START);

        occupied = 0;

        for (int i = 0; i < oldKeys.length; i++) {
            int key = oldKeys[i];
            if (key == 0)
                continue;

            int slot = findSlot(key);
            keys[slot] = key;
            retainedCounts[slot] = oldRetained[i];
            validCounts[slot] = oldValid[i];
            onlyValidRows[slot] = oldOnly[i];
            startTimes[slot] = oldStarts[i];
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
