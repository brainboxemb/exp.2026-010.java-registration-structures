package emb.experiment.ranking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class LongArrayListTest {
    @Test
    void growsGeometricallyAndRemovesWithoutPreservingOrder() {
        LongArrayList list = new LongArrayList(2);
        list.add(10);
        list.add(20);

        assertEquals(2, list.size());
        assertEquals(2, list.capacity());
        assertEquals(0, list.growthEvents());

        list.add(30);
        assertEquals(3, list.size());
        assertEquals(4, list.capacity());
        assertEquals(1, list.growthEvents());

        assertTrue(list.removeOneUnordered(20));
        assertEquals(2, list.size());
        assertFalse(list.removeOneUnordered(99));
    }
}
