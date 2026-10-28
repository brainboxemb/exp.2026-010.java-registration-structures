package emb.experiment.registration;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

final class RegistrationProjectionTest {
    private static List<String> snapshot(RegistrationProjection p) {
        List<String> result = new ArrayList<String>();
        p.visitAfter(0, Integer.MAX_VALUE, (seq, id, passage, elapsed, valid, rank) ->
                result.add(seq + ":" + id + ":" + passage + ":" + elapsed + ":" + valid + ":" + rank));
        return result;
    }

    @Test void duplicatesRevStartsAndCapacityBoundary() {
        for (RegistrationProjection.RankingMode mode : RegistrationProjection.RankingMode.values()) {
            RegistrationProjection p = new RegistrationProjection(mode, 2);
            assertTrue(p.setStart(1, 1000));
            p.add(1, 1, 2000); p.add(2, 1, 2000); p.add(3, 1, 3000);
            assertEquals(3, p.size());
            assertEquals(1, p.rowGrowthEvents());
            assertEquals(3, p.rankableCount());
            assertTrue(snapshot(p).get(1).endsWith(":true:1"));
            assertTrue(snapshot(p).get(2).endsWith(":true:3"));
            assertTrue(p.revoke(4, 1, 2000));
            assertFalse(p.revoke(5, 1, 2000) && p.size() != 3);
            assertEquals(3, p.size());
            assertTrue(snapshot(p).get(1).endsWith(":false:0"));
            assertTrue(p.deleteStart(1));
            assertEquals(0, p.rankableCount());
            assertTrue(p.setStart(1, 2500));
            assertEquals(1, p.rankableCount());
            assertEquals(1, p.lastRevokeSequence());
        }
    }

    @Test void differentialDeterministicMutationWorkload() {
        RegistrationProjection reference = new RegistrationProjection(RegistrationProjection.RankingMode.REBUILD, 16);
        RegistrationProjection candidate = new RegistrationProjection(RegistrationProjection.RankingMode.SORTED_ARRAY, 16);
        Random random = new Random(0x2026010L);
        long sequence = 0;
        for (int operation = 0; operation < 3500; operation++) {
            int id = random.nextInt(70);
            long millis = 1_000_000L + random.nextInt(40) * 1000L;
            int type = random.nextInt(10);
            if (type < 6) {
                reference.add(++sequence, id, millis);
                candidate.add(sequence, id, millis);
            } else if (type < 8) {
                assertEquals(reference.revoke(++sequence, id, millis),
                        candidate.revoke(sequence, id, millis));
            } else if (type == 8) {
                long start = 999_000L + random.nextInt(40) * 1000L;
                assertEquals(reference.setStart(id, start), candidate.setStart(id, start));
            } else {
                assertEquals(reference.deleteStart(id), candidate.deleteStart(id));
            }
            assertEquals(reference.size(), candidate.size());
            assertEquals(reference.rankableCount(), candidate.rankableCount());
            assertEquals(snapshot(reference), snapshot(candidate), "operation " + operation);
            assertEquals(reference.rankingRevision(), candidate.rankingRevision());
            assertEquals(reference.stateRevision(), candidate.stateRevision());
        }
        assertTrue(reference.size() > 512);
        assertEquals(reference.visitAfter(100, 25, (s, id, p, e, valid, r) -> {}),
                candidate.visitAfter(100, 25, (s, id, p, e, valid, r) -> {}));
    }
}
