package emb.experiment.registration;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class ChangeJournalTest {
    private static List<String> deltas(RegistrationProjection projection, long after, int limit) {
        List<String> events = new ArrayList<String>();
        int count = projection.visitChangesAfter(after, limit,
                (revision, kind, id, mutationSequence, affectedAddSequence, ranks) ->
                        events.add(revision + ":" + kind + ":" + id + ":"
                                + mutationSequence + ":" + affectedAddSequence + ":" + ranks));
        assertEquals(events.size(), count);
        return events;
    }

    @Test void publishesActualChangesWithoutAllocatingPerEventAndDetectsHistoryExpiry() {
        for (RegistrationProjection.RankingMode mode : RegistrationProjection.RankingMode.values()) {
            RegistrationProjection p = new RegistrationProjection(mode, 2, 3);
            p.add(1, 7, 1200); // no stage start yet
            assertEquals(1, p.stateRevision());
            assertEquals(0, p.rankingRevision());
            assertTrue(p.setStart(7, 1000));
            assertEquals(1, p.rankingRevision());
            p.add(2, 7, 1300);
            assertTrue(p.revoke(3, 7, 1300));
            assertEquals(4, p.stateRevision());
            assertEquals(1, p.oldestUsableChangeCursor());

            assertEquals("2:START_SET:7:0:0:true", deltas(p, 1, 10).get(0));
            assertEquals("3:ADDED:7:2:2:true", deltas(p, 1, 10).get(1));
            assertEquals("4:REVOKED:7:3:2:true", deltas(p, 1, 10).get(2));
            assertEquals(2, deltas(p, 1, 2).size());
            assertEquals(1, deltas(p, 3, 2).size());
            assertEquals(0, deltas(p, 4, 2).size());
            assertThrows(ChangeJournal.HistoryExpiredException.class, () -> deltas(p, 0, 3));

            long before = p.stateRevision();
            assertFalse(p.setStart(7, 1000));
            assertFalse(p.revoke(4, 99, 5000));
            assertFalse(p.deleteStart(99));
            assertEquals(before, p.stateRevision());
            assertTrue(p.deleteStart(7));
            assertEquals("5:START_DELETED:7:0:0:true", deltas(p, 4, 2).get(0));
            assertEquals(2, p.oldestUsableChangeCursor());
            assertThrows(ChangeJournal.HistoryExpiredException.class, () -> deltas(p, 1, 1));
        }
    }

    @Test void unrelatedStartEventDoesNotInvalidateRanking() {
        for (RegistrationProjection.RankingMode mode : RegistrationProjection.RankingMode.values()) {
            RegistrationProjection p = new RegistrationProjection(mode, 2, 8);
            assertTrue(p.setStart(500, 1000));
            assertEquals("1:START_SET:500:0:0:false", deltas(p, 0, 1).get(0));
            assertEquals(0, p.rankingRevision());
            assertThrows(IllegalArgumentException.class, () -> deltas(p, 2, 1));
            assertThrows(IllegalArgumentException.class, () -> deltas(p, 0, -1));
        }
    }
}
