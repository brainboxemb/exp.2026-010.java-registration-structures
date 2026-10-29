package emb.experiment.active;

import emb.experiment.ranking.RankingIndexKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RegistrationListTest {
    private static final int A1 = ('A' << 16) | 1;
    private static final int A2 = ('A' << 16) | 2;
    private static final int A3 = ('A' << 16) | 3;

    @Test
    void visitorGetsCurrentRankWithoutRankBeingStoredInRows() {
        for (RankingIndexKind kind : RankingIndexKind.values()) {
            RegistrationList list = list(kind, 8, 16, 16);

            assertTrue(list.setStart(A1, 1000));
            assertTrue(list.setStart(A2, 1000));
            assertTrue(list.setStart(A3, 1000));

            list.add(1, A1, 1100);
            list.add(2, A2, 1100);
            list.add(3, A3, 1200);

            assertEquals(3, list.size());
            assertEquals(3, list.uniqueRetainedRegistrationCount());
            assertEquals(3, list.uniqueValidRegistrationCount());

            List<String> rows = rows(list);
            assertEquals("0:" + A1 + ":100:true:1", rows.get(0), kind.name());
            assertEquals("1:" + A2 + ":100:true:1", rows.get(1), kind.name());
            assertEquals("2:" + A3 + ":200:true:3", rows.get(2), kind.name());

            assertTrue(list.revoke(4, A1, 1100));
            rows = rows(list);

            assertEquals("0:" + A1 + ":100:false:0", rows.get(0), kind.name());
            assertEquals("1:" + A2 + ":100:true:1", rows.get(1), kind.name());
            assertEquals("2:" + A3 + ":200:true:2", rows.get(2), kind.name());
            assertEquals(3, list.uniqueRetainedRegistrationCount());
            assertEquals(2, list.uniqueValidRegistrationCount());
        }
    }

    @Test
    void oneValidRegistrationUsesDirectRowAndMultipleUsesExceptionalScan() {
        for (RankingIndexKind kind : RankingIndexKind.values()) {
            RegistrationList list = list(kind, 8, 16, 16);
            list.setStart(A1, 1000);

            assertEquals(0, list.add(1, A1, 1100));
            assertEquals(1, list.validCount(A1));

            assertEquals(1, list.add(2, A1, 1200));
            assertEquals(2, list.validCount(A1));

            assertTrue(list.revoke(3, A1, 1200));
            assertEquals(1, list.validCount(A1));
            assertEquals(1, list.scannedRevokeCount());

            assertTrue(list.revoke(4, A1, 1100));
            assertEquals(0, list.validCount(A1));
            assertEquals(1, list.fastRevokeCount());
            assertEquals(1, list.scannedRevokeCount());

            List<String> rows = rows(list);
            assertTrue(rows.get(0).contains(":false:0"));
            assertTrue(rows.get(1).contains(":false:0"));
        }
    }

    @Test
    void startChangeScansAffectedRowsAndUpdatesRankingIndex() {
        for (RankingIndexKind kind : RankingIndexKind.values()) {
            RegistrationList list = list(kind, 8, 16, 16);
            list.setStart(A1, 1000);
            list.setStart(A2, 1000);

            list.add(1, A1, 1100);
            list.add(2, A1, 1200);
            list.add(3, A2, 1150);

            assertEquals(
                    listOf(
                            "0:" + A1 + ":100:true:1",
                            "1:" + A1 + ":200:true:3",
                            "2:" + A2 + ":150:true:2"),
                    rows(list),
                    kind.name());

            long before = list.latestMutationRevision();
            assertTrue(list.setStart(A1, 1050));
            assertEquals(before + 1, list.latestMutationRevision());

            assertEquals(
                    listOf(
                            "0:" + A1 + ":50:true:1",
                            "1:" + A1 + ":150:true:2",
                            "2:" + A2 + ":150:true:2"),
                    rows(list),
                    kind.name());

            List<String> mutations = mutations(list, before);
            assertEquals(1, mutations.size());
            assertEquals((before + 1) + ":START_CHANGED:" + A1 + ":1", mutations.get(0));

            assertTrue(list.deleteStart(A1));
            assertEquals(
                    listOf(
                            "0:" + A1 + ":" + RegistrationList.NO_TIME + ":true:0",
                            "1:" + A1 + ":" + RegistrationList.NO_TIME + ":true:0",
                            "2:" + A2 + ":150:true:1"),
                    rows(list),
                    kind.name());
        }
    }

    @Test
    void cachedStartTimeChangesEvenWhenElapsedStaysUnknown() {
        RegistrationList list = list(RankingIndexKind.SORTED_ARRAY, 8, 16, 8);

        assertTrue(list.setStart(A1, 2000));
        list.add(1, A1, 1000);

        final long[] observed = new long[2];
        list.visitRows(0, 1, (row, seq, id, passage, start, elapsed, valid, rank) -> {
            observed[0] = start;
            observed[1] = elapsed;
        });

        assertEquals(2000, observed[0]);
        assertEquals(RegistrationList.NO_TIME, observed[1]);

        assertTrue(list.setStart(A1, 3000));
        list.visitRows(0, 1, (row, seq, id, passage, start, elapsed, valid, rank) -> {
            observed[0] = start;
            observed[1] = elapsed;
        });

        assertEquals(3000, observed[0]);
        assertEquals(RegistrationList.NO_TIME, observed[1]);
    }

    @Test
    void snapshotMarkerAndMutationStreamFormOneSynchronizationCursor() {
        RegistrationList list = list(RankingIndexKind.SORTED_ARRAY, 8, 16, 3);
        list.setStart(A1, 1000);

        list.add(1, A1, 1100);
        list.add(2, A2, 1200);

        List<String> snapshotRows = new ArrayList<String>();
        long marker = list.visitSnapshot((row, seq, id, passage, start, elapsed, valid, rank) ->
                snapshotRows.add(row + ":" + seq + ":" + id + ":" + valid + ":" + rank));

        assertEquals(2, marker);
        assertEquals(2, snapshotRows.size());

        list.revoke(3, A1, 1100);
        list.add(4, A3, 1300);

        assertEquals(
                listOf("3:ROW_REVOKED:0:1", "4:ROW_ADDED:2:0"),
                mutations(list, marker));

        assertEquals(2, list.oldestAvailableMutationRevision());
        assertThrows(
                RegistrationMutations.HistoryExpiredException.class,
                () -> list.visitMutationsAfter(0, 10, (r, k, s, f) -> {}));

        List<String> secondSnapshot = new ArrayList<String>();
        long newMarker = list.visitSnapshot((row, seq, id, passage, start, elapsed, valid, rank) ->
                secondSnapshot.add(row + ":" + valid + ":" + rank));

        assertEquals(4, newMarker);
        assertEquals(3, secondSnapshot.size());
        assertTrue(mutations(list, newMarker).isEmpty());
    }

    @Test
    void startOnlyIndexEntryDoesNotCountAsRetainedRegistrationAndExistingIdDoesNotRehash() {
        RegistrationList list = list(RankingIndexKind.SORTED_ARRAY, 2, 2, 8);

        assertTrue(list.setStart(A1, 1000));
        assertEquals(0, list.uniqueRetainedRegistrationCount());
        assertEquals(0, list.uniqueValidRegistrationCount());
        assertEquals(2, list.indexCapacity());
        assertEquals(0, list.indexGrowthEvents());

        assertTrue(list.setStart(A1, 1100));
        assertEquals(2, list.indexCapacity());
        assertEquals(0, list.indexGrowthEvents());

        list.add(1, A1, 1200);
        assertEquals(1, list.uniqueRetainedRegistrationCount());
        assertEquals(1, list.uniqueValidRegistrationCount());

        list.add(2, A2, 1300);
        assertEquals(4, list.indexCapacity());
        assertEquals(1, list.indexGrowthEvents());

        list.add(3, A3, 1400);
        assertEquals(4, list.rowCapacity());
        assertEquals(1, list.rowGrowthEvents());
    }

    @Test
    void unmatchedRevConsumesLogSequenceButDoesNotCreateDisplayMutation() {
        RegistrationList list = list(RankingIndexKind.SORTED_ARRAY, 8, 16, 8);
        list.add(1, A1, 1100);

        long mutationBefore = list.latestMutationRevision();
        assertFalse(list.revoke(2, A1, 9999));

        assertEquals(2, list.lastLogSequence());
        assertEquals(mutationBefore, list.latestMutationRevision());
        assertEquals(1, list.validCount(A1));

        assertThrows(IllegalArgumentException.class, () -> list.add(2, A2, 1200));
    }

    private static RegistrationList list(
            RankingIndexKind kind,
            int rowCapacity,
            int indexCapacity,
            int mutationCapacity) {

        return new RegistrationList(
                rowCapacity,
                indexCapacity,
                mutationCapacity,
                kind.create(rowCapacity));
    }

    private static List<String> rows(RegistrationList list) {
        List<String> result = new ArrayList<String>();
        list.visitRows(0, Integer.MAX_VALUE,
                (row, seq, id, passage, start, elapsed, valid, rank) ->
                        result.add(row + ":" + id + ":" + elapsed + ":" + valid + ":" + rank));
        return result;
    }

    private static List<String> mutations(RegistrationList list, long after) {
        List<String> result = new ArrayList<String>();
        list.visitMutationsAfter(after, Integer.MAX_VALUE,
                (revision, kind, subject, flags) ->
                        result.add(revision + ":" + kind + ":" + subject + ":" + flags));
        return result;
    }

    private static List<String> listOf(String... values) {
        List<String> result = new ArrayList<String>();
        for (String value : values)
            result.add(value);
        return result;
    }
}
