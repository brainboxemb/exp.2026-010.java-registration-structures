package emb.experiment.active;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RegistrationIndexTest {
    private static final int A1 = ('A' << 16) | 1;
    private static final int A2 = ('A' << 16) | 2;

    @Test
    void singleRetainedAndSingleValidRowsHaveDirectPositions() {
        RegistrationIndex index = new RegistrationIndex(4);
        int slot = index.ensureSlot(A1);

        assertEquals(0, index.retainedCount(slot));
        assertEquals(0, index.validCount(slot));
        assertEquals(RegistrationIndex.NO_ROW, index.onlyRetainedRow(slot));
        assertEquals(RegistrationIndex.NO_ROW, index.onlyValidRow(slot));

        index.rowAdded(slot, 17);

        assertEquals(1, index.retainedCount(slot));
        assertEquals(1, index.validCount(slot));
        assertEquals(17, index.onlyRetainedRow(slot));
        assertEquals(17, index.onlyValidRow(slot));

        index.rowAdded(slot, 23);

        assertEquals(2, index.retainedCount(slot));
        assertEquals(2, index.validCount(slot));
        assertEquals(RegistrationIndex.NO_ROW, index.onlyRetainedRow(slot));
        assertEquals(RegistrationIndex.NO_ROW, index.onlyValidRow(slot));

        assertEquals(1, index.rowRevoked(slot));
        assertEquals(RegistrationIndex.NO_ROW, index.onlyValidRow(slot));

        index.setOnlyValidRow(slot, 17);
        assertEquals(17, index.onlyValidRow(slot));

        // Retained rows never disappear, so the retained fast path stays disabled.
        assertEquals(2, index.retainedCount(slot));
        assertEquals(RegistrationIndex.NO_ROW, index.onlyRetainedRow(slot));
    }

    @Test
    void rehashPreservesDirectPositions() {
        RegistrationIndex index = new RegistrationIndex(2);

        int first = index.ensureSlot(A1);
        index.rowAdded(first, 9);

        // Second distinct key crosses the 0.5 load factor and rehashes.
        int second = index.ensureSlot(A2);
        index.rowAdded(second, 11);

        int movedFirst = index.findExistingSlot(A1);
        assertEquals(1, index.retainedCount(movedFirst));
        assertEquals(1, index.validCount(movedFirst));
        assertEquals(9, index.onlyRetainedRow(movedFirst));
        assertEquals(9, index.onlyValidRow(movedFirst));

        int movedSecond = index.findExistingSlot(A2);
        assertEquals(11, index.onlyRetainedRow(movedSecond));
        assertEquals(11, index.onlyValidRow(movedSecond));
        assertEquals(1, index.growthEvents());
    }
}
