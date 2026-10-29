package emb.experiment.registration;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class LogBookProjectionSessionTest {
    private static final int A1 = ('A' << 16) | 1;
    private static final int A2 = ('A' << 16) | 2;
    private static final int A3 = ('A' << 16) | 3;
    private static final int A4 = ('A' << 16) | 4;

    private static LogBookProjectionSession.Fact fact(
            long sequence, int location, LogBookProjectionSession.Kind kind,
            LogBookProjectionSession.Action action, int id, long time) {
        return new LogBookProjectionSession.Fact(sequence, location, kind, action, id, time);
    }

    private static List<String> snapshot(ProjectionModel p) {
        List<String> result = new ArrayList<String>();
        p.visitAfter(0, 50, (seq, id, passage, elapsed, valid, rank) ->
                result.add(seq + ":" + id + ":" + elapsed + ":" + valid + ":" + rank));
        return result;
    }

    private static List<String> events(ProjectionModel p, long after) {
        List<String> result = new ArrayList<String>();
        p.visitChangesAfter(after, 20, (rev, kind, id, mutSeq, affected, rankingDirty) ->
                result.add(rev + ":" + kind + ":" + id + ":" + mutSeq
                        + ":" + affected + ":" + rankingDirty));
        return result;
    }

    @Test void openFiltersWholeLogBookRebuildIsSilentAndCloseDoesNotEraseHistory() {
        for (ModelKind kind : ModelKind.values()) {
            LogBookProjectionSession session = new LogBookProjectionSession(kind, 2);
            assertTrue(session.setStart(A1, 900));
            assertTrue(session.setStart(A3, 900));
            assertTrue(session.setStart(A4, 900));

            session.append(fact(1, 1, LogBookProjectionSession.Kind.AUTOMATIC,
                    LogBookProjectionSession.Action.ADD, A1, 1000));
            session.append(fact(2, 2, LogBookProjectionSession.Kind.AUTOMATIC,
                    LogBookProjectionSession.Action.ADD, A2, 1100));
            session.append(fact(3, 1, LogBookProjectionSession.Kind.MANUAL,
                    LogBookProjectionSession.Action.ADD, A1, 1000));
            session.append(fact(4, 1, LogBookProjectionSession.Kind.NODE_OPEN,
                    null, 0, 900));
            session.append(fact(5, 1, LogBookProjectionSession.Kind.AUTOMATIC,
                    LogBookProjectionSession.Action.REV, A1, 1000));
            session.append(fact(6, 1, LogBookProjectionSession.Kind.AUTOMATIC,
                    LogBookProjectionSession.Action.ADD, A3, 1200));
            session.append(fact(7, 1, LogBookProjectionSession.Kind.MANUAL,
                    LogBookProjectionSession.Action.ADD, A4, 1100));

            ProjectionModel p = session.open(1);
            assertEquals(1, session.activeLocation());
            assertEquals(Arrays.asList(
                    "1:" + A1 + ":100:true:1",
                    "3:" + A1 + ":100:false:0",
                    "6:" + A3 + ":300:true:3",
                    "7:" + A4 + ":200:true:2"), snapshot(p), kind.name());
            assertEquals(4, p.size());
            assertEquals(3, p.rankableCount());
            long snapshotCursor = p.stateRevision();
            assertEquals(snapshotCursor, p.oldestUsableChangeCursor());
            assertTrue(events(p, snapshotCursor).isEmpty());
            assertThrows(ChangeJournal.HistoryExpiredException.class, () -> events(p, 0L));
            assertEquals(5L, p.lastRevokeSequence());
            assertEquals(7L, p.lastMutationSequence());

            // Foreign location facts must not advance active projection revisions.
            session.append(fact(8, 2, LogBookProjectionSession.Kind.MANUAL,
                    LogBookProjectionSession.Action.ADD, A2, 1300));
            assertEquals(snapshotCursor, p.stateRevision());

            session.append(fact(9, 1, LogBookProjectionSession.Kind.AUTOMATIC,
                    LogBookProjectionSession.Action.ADD, A4, 1000));
            assertEquals(1, events(p, snapshotCursor).size());
            assertTrue(events(p, snapshotCursor).get(0).endsWith(":9:9:true"));
            assertEquals(1, snapshot(p).stream().filter(s -> s.startsWith("9:") && s.endsWith(":true:1")).count());

            long afterLiveAdd = p.stateRevision();
            assertTrue(session.setStart(A3, 1050));
            assertEquals(1, events(p, afterLiveAdd).size());
            assertTrue(events(p, afterLiveAdd).get(0).endsWith(":0:0:true"));
            assertFalse(session.setStart(A3, 1050));
            assertEquals(afterLiveAdd + 1, p.stateRevision());

            session.close();
            assertEquals(0, session.activeLocation());
            assertThrows(IllegalStateException.class, session::active);
            session.append(fact(10, 2, LogBookProjectionSession.Kind.AUTOMATIC,
                    LogBookProjectionSession.Action.ADD, A2, 1400));
            session.append(fact(11, 1, LogBookProjectionSession.Kind.NODE_CLOSE,
                    null, 0, 1500));

            ProjectionModel wp2 = session.open(2);
            assertEquals(3, wp2.size());
            assertEquals(0, wp2.rankableCount());
            assertEquals(Arrays.asList(
                    "2:" + A2 + ":-1:true:0",
                    "8:" + A2 + ":-1:true:0",
                    "10:" + A2 + ":-1:true:0"), snapshot(wp2));
            assertTrue(events(wp2, wp2.stateRevision()).isEmpty());
            assertEquals(11, session.logBookSize());

            ProjectionModel wp1Again = session.open(1);
            assertNotSame(p, wp1Again);
            assertEquals(5, wp1Again.size());
            assertTrue(events(wp1Again, wp1Again.stateRevision()).isEmpty());
            assertEquals(9L, wp1Again.lastMutationSequence());
        }
    }

    @Test void validatesFactsAndEnsuresStrictLogBookSequence() {
        LogBookProjectionSession session = new LogBookProjectionSession(ModelKind.PACKED_SORTED, 4);
        session.append(fact(5, 1, LogBookProjectionSession.Kind.AUTOMATIC,
                LogBookProjectionSession.Action.ADD, A1, 1000));
        assertThrows(IllegalArgumentException.class, () ->
                session.append(fact(4, 1, LogBookProjectionSession.Kind.AUTOMATIC,
                        LogBookProjectionSession.Action.ADD, A1, 1000)));
        assertThrows(IllegalArgumentException.class, () ->
                fact(6, 1, LogBookProjectionSession.Kind.NODE_OPEN,
                        LogBookProjectionSession.Action.ADD, A1, 1000));
        assertThrows(IllegalArgumentException.class, () ->
                fact(6, 1, LogBookProjectionSession.Kind.MANUAL, null, A1, 1000));
        assertEquals(1, session.logBookSize());
    }
}
