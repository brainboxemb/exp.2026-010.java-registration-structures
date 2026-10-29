package emb.experiment.active;

import emb.experiment.ranking.RankingIndexKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the intended display protocol:
 *
 * full snapshot -> mutation cursor -> row/id refreshes -> rank refresh when dirty.
 */
final class DisplaySynchronizationTest {
    private static final long BASE =
            Instant.parse("2026-10-29T12:00:00Z").toEpochMilli();

    private static final int A1 = ('A' << 16) | 1;
    private static final int A2 = ('A' << 16) | 2;
    private static final int A3 = ('A' << 16) | 3;

    private static final class ReplicaRow {
        long sequence;
        int id;
        long passage;
        long start;
        long elapsed;
        boolean valid;
        int rank;

        String value() {
            return sequence + ":" + id + ":" + passage + ":" + start + ":"
                    + elapsed + ":" + valid + ":" + rank;
        }
    }

    private static final class DisplayReplica {
        final List<ReplicaRow> rows = new ArrayList<ReplicaRow>();
        long cursor;

        void replaceFromSnapshot(RegistrationList source) {
            rows.clear();

            cursor = source.visitSnapshot(
                    (row, sequence, id, passage, start, elapsed, valid, rank) ->
                            store(row, sequence, id, passage, start, elapsed, valid, rank));
        }

        void synchronize(RegistrationList source) {
            final boolean[] rankingDirty = {false};

            int visited;
            do {
                final long batchStart = cursor;
                final long[] newest = {batchStart};

                visited = source.visitMutationsAfter(
                        batchStart,
                        8,
                        (revision, kind, subject, flags) -> {
                            newest[0] = revision;

                            switch (kind) {
                                case ROW_ADDED:
                                case ROW_REVOKED:
                                    source.visitRow(
                                            subject,
                                            (row, sequence, id, passage, start, elapsed, valid, rank) ->
                                                    store(
                                                            row,
                                                            sequence,
                                                            id,
                                                            passage,
                                                            start,
                                                            elapsed,
                                                            valid,
                                                            rank));
                                    break;

                                case START_CHANGED:
                                    source.visitRegistration(
                                            subject,
                                            (row, sequence, id, passage, start, elapsed, valid, rank) ->
                                                    store(
                                                            row,
                                                            sequence,
                                                            id,
                                                            passage,
                                                            start,
                                                            elapsed,
                                                            valid,
                                                            rank));
                                    break;

                                default:
                                    throw new AssertionError(kind);
                            }

                            if ((flags & RegistrationMutations.RANKING_CHANGED) != 0)
                                rankingDirty[0] = true;
                        });

                cursor = newest[0];
            } while (visited == 8);

            if (rankingDirty[0]) {
                // This test replica keeps every row, so refresh every rank.
                // A real display only needs to refresh rows it currently presents.
                source.visitRows(
                        0,
                        Integer.MAX_VALUE,
                        (row, sequence, id, passage, start, elapsed, valid, rank) ->
                                store(
                                        row,
                                        sequence,
                                        id,
                                        passage,
                                        start,
                                        elapsed,
                                        valid,
                                        rank));
            }
        }

        private void store(
                int row,
                long sequence,
                int id,
                long passage,
                long start,
                long elapsed,
                boolean valid,
                int rank) {

            while (rows.size() <= row)
                rows.add(new ReplicaRow());

            ReplicaRow target = rows.get(row);
            target.sequence = sequence;
            target.id = id;
            target.passage = passage;
            target.start = start;
            target.elapsed = elapsed;
            target.valid = valid;
            target.rank = rank;
        }
    }

    @Test
    void mutationsConvergeToFreshSnapshotAfterAddRevokeAndStartChange() {
        RegistrationList source = list();

        source.beginInitialLoad();
        source.setStart(A1, start(0));
        source.setStart(A2, start(0));
        source.add(1, A1, passage(100));
        source.add(2, A2, passage(200));
        source.finishInitialLoad();

        DisplayReplica replica = new DisplayReplica();
        replica.replaceFromSnapshot(source);
        assertEquals(0, replica.cursor);

        // Faster ADD changes ranks of existing rows.
        source.setStart(A3, start(0));
        source.add(3, A3, passage(50));

        // REV changes the rank set but keeps row 0 stable.
        assertTrue(source.revoke(4, A1, passage(100)));

        // Start change changes row 1 elapsed and ranking again.
        assertTrue(source.setStart(A2, start(180)));

        replica.synchronize(source);

        assertEquals(source.latestMutationRevision(), replica.cursor);
        assertEquals(snapshotValues(source), replicaValues(replica));

        // Expected current competition ranking:
        // A1 invalid, A2 elapsed 20 => rank 1, A3 elapsed 50 => rank 2.
        assertEquals(0, replica.rows.get(0).rank);
        assertEquals(1, replica.rows.get(1).rank);
        assertEquals(2, replica.rows.get(2).rank);
    }

    @Test
    void multipleMutationsCanBeConsumedInBoundedBatches() {
        RegistrationList source = list();
        source.beginInitialLoad();

        for (int i = 0; i < 12; i++) {
            int id = ('A' << 16) | (100 + i);
            source.setStart(id, start(0));
            source.add(i + 1L, id, passage(100 + i * 10L));
        }

        source.finishInitialLoad();

        DisplayReplica replica = new DisplayReplica();
        replica.replaceFromSnapshot(source);

        // Create more than the replica's mutation batch size (8).
        for (int i = 0; i < 12; i++) {
            int id = ('A' << 16) | (100 + i);
            source.setStart(id, start(10));
        }

        replica.synchronize(source);

        assertEquals(12, source.latestMutationRevision());
        assertEquals(12, replica.cursor);
        assertEquals(snapshotValues(source), replicaValues(replica));
    }

    private static RegistrationList list() {
        return new RegistrationList(
                64,
                128,
                64,
                RankingIndexKind.SORTED_ARRAY.create(64));
    }

    private static List<String> snapshotValues(RegistrationList source) {
        List<String> result = new ArrayList<String>();
        source.visitSnapshot(
                (row, sequence, id, passage, start, elapsed, valid, rank) ->
                        result.add(
                                sequence + ":" + id + ":" + passage + ":" + start + ":"
                                        + elapsed + ":" + valid + ":" + rank));
        return result;
    }

    private static List<String> replicaValues(DisplayReplica replica) {
        List<String> result = new ArrayList<String>();
        for (ReplicaRow row : replica.rows)
            result.add(row.value());
        return result;
    }

    private static long passage(long elapsedMillis) {
        return BASE + elapsedMillis;
    }

    private static long start(long offsetMillis) {
        return StageTiming.compactUtc(
                Instant.ofEpochMilli(BASE + offsetMillis));
    }
}
