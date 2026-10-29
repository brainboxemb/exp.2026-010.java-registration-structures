package emb.experiment.active;

import emb.experiment.ranking.RankingIndexKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class RegistrationListOracleTest {
    private static final long BASE = 1_700_000_000_000L;
    private static final int ID_BASE = ('A' << 16);

    private static final class Row {
        final long sequence;
        final int id;
        final long passage;
        long start;
        long elapsed;
        boolean valid = true;

        Row(long sequence, int id, long passage, long start) {
            this.sequence = sequence;
            this.id = id;
            this.passage = passage;
            this.start = start;
            this.elapsed = elapsed(start, passage);
        }
    }

    /**
     * Intentionally simple reference model.
     *
     * It uses normal Java collections and recalculates ranks by scanning every
     * row. It shares no search/ranking structure with RegistrationList.
     */
    private static final class Oracle {
        final List<Row> rows = new ArrayList<Row>();
        final Map<Integer, Long> starts = new HashMap<Integer, Long>();
        long lastLogSequence;
        long mutationRevision;

        void add(long sequence, int id, long passage) {
            requireNext(sequence);
            Long start = starts.get(id);
            rows.add(new Row(
                    sequence,
                    id,
                    passage,
                    start == null ? RegistrationList.NO_TIME : start.longValue()));
            lastLogSequence = sequence;
            mutationRevision++;
        }

        boolean revoke(long sequence, int id, long passage) {
            requireNext(sequence);
            lastLogSequence = sequence;

            for (int i = rows.size() - 1; i >= 0; i--) {
                Row row = rows.get(i);
                if (row.valid && row.id == id && row.passage == passage) {
                    row.valid = false;
                    mutationRevision++;
                    return true;
                }
            }
            return false;
        }

        boolean setStart(int id, long start) {
            Long previous = starts.put(id, start);
            if (previous != null && previous.longValue() == start)
                return false;

            boolean hasRetainedRow = false;
            for (Row row : rows) {
                if (row.id != id)
                    continue;

                hasRetainedRow = true;
                row.start = start;
                row.elapsed = elapsed(start, row.passage);
            }

            if (hasRetainedRow)
                mutationRevision++;
            return true;
        }

        boolean deleteStart(int id) {
            if (starts.remove(id) == null)
                return false;

            boolean hasRetainedRow = false;
            for (Row row : rows) {
                if (row.id != id)
                    continue;

                hasRetainedRow = true;
                row.start = RegistrationList.NO_TIME;
                row.elapsed = RegistrationList.NO_TIME;
            }

            if (hasRetainedRow)
                mutationRevision++;
            return true;
        }

        int rank(Row target) {
            if (!target.valid || target.elapsed == RegistrationList.NO_TIME)
                return 0;

            int faster = 0;
            for (Row row : rows) {
                if (row.valid
                        && row.elapsed != RegistrationList.NO_TIME
                        && row.elapsed < target.elapsed) {
                    faster++;
                }
            }
            return faster + 1;
        }

        int uniqueRetainedCount() {
            Set<Integer> ids = new HashSet<Integer>();
            for (Row row : rows)
                ids.add(row.id);
            return ids.size();
        }

        int uniqueValidCount() {
            Set<Integer> ids = new HashSet<Integer>();
            for (Row row : rows) {
                if (row.valid)
                    ids.add(row.id);
            }
            return ids.size();
        }

        int validCount(int id) {
            int count = 0;
            for (Row row : rows) {
                if (row.id == id && row.valid)
                    count++;
            }
            return count;
        }

        int retainedCount(int id) {
            int count = 0;
            for (Row row : rows) {
                if (row.id == id)
                    count++;
            }
            return count;
        }

        Row randomRow(Random random, boolean requireValid) {
            if (rows.isEmpty())
                return null;

            for (int attempt = 0; attempt < 50; attempt++) {
                Row row = rows.get(random.nextInt(rows.size()));
                if (!requireValid || row.valid)
                    return row;
            }

            for (Row row : rows) {
                if (!requireValid || row.valid)
                    return row;
            }
            return null;
        }

        private void requireNext(long sequence) {
            if (sequence <= lastLogSequence)
                throw new IllegalArgumentException("sequence must increase");
        }
    }

    @Test
    void seededMixedMutationsMatchIndependentOracleForEveryRankingIndex() {
        for (RankingIndexKind kind : RankingIndexKind.values()) {
            RegistrationList actual = new RegistrationList(
                    16,
                    16,
                    8192,
                    kind.create(16));
            Oracle oracle = new Oracle();
            Random random = new Random(0x0102026L);
            long sequence = 0;

            // Starts may exist before any registration and should not count as
            // retained/valid registrations or display mutations.
            for (int i = 0; i < 20; i++) {
                int id = id(i);
                long start = BASE + random.nextInt(2000);
                assertEquals(oracle.setStart(id, start), actual.setStart(id, start), kind.name());
            }
            assertEquivalent(kind, actual, oracle);

            for (int operation = 0; operation < 2500; operation++) {
                int choice = random.nextInt(100);

                if (choice < 48 || oracle.rows.isEmpty()) {
                    int id = id(random.nextInt(80));
                    long passage = BASE + random.nextInt(20_000);
                    sequence++;
                    oracle.add(sequence, id, passage);
                    actual.add(sequence, id, passage);
                } else if (choice < 70) {
                    boolean useReal = random.nextInt(10) < 8;
                    Row row = useReal ? oracle.randomRow(random, true) : null;
                    int id;
                    long passage;

                    if (row != null) {
                        id = row.id;
                        passage = row.passage;
                    } else {
                        id = id(random.nextInt(80));
                        passage = BASE + 100_000 + random.nextInt(10_000);
                    }

                    sequence++;
                    assertEquals(
                            oracle.revoke(sequence, id, passage),
                            actual.revoke(sequence, id, passage),
                            kind + " REV operation " + operation);
                } else if (choice < 90) {
                    int id = id(random.nextInt(80));
                    long start = BASE + random.nextInt(5000);
                    assertEquals(
                            oracle.setStart(id, start),
                            actual.setStart(id, start),
                            kind + " START_SET operation " + operation);
                } else {
                    int id = id(random.nextInt(80));
                    assertEquals(
                            oracle.deleteStart(id),
                            actual.deleteStart(id),
                            kind + " START_DELETE operation " + operation);
                }

                if (operation < 50 || operation % 29 == 0)
                    assertEquivalent(kind, actual, oracle);
            }

            assertEquivalent(kind, actual, oracle);
        }
    }

    private static void assertEquivalent(
            RankingIndexKind kind,
            RegistrationList actual,
            Oracle oracle) {

        assertEquals(oracle.rows.size(), actual.size(), kind.name());
        assertEquals(oracle.uniqueRetainedCount(), actual.uniqueRetainedRegistrationCount(), kind.name());
        assertEquals(oracle.uniqueValidCount(), actual.uniqueValidRegistrationCount(), kind.name());
        assertEquals(oracle.lastLogSequence, actual.lastLogSequence(), kind.name());
        assertEquals(oracle.mutationRevision, actual.latestMutationRevision(), kind.name());

        final int[] visited = {0};
        long marker = actual.visitSnapshot(
                (row, sequence, id, passage, start, elapsed, valid, rank) -> {
                    Row expected = oracle.rows.get(row);

                    assertEquals(expected.sequence, sequence, kind + " row " + row + " sequence");
                    assertEquals(expected.id, id, kind + " row " + row + " id");
                    assertEquals(expected.passage, passage, kind + " row " + row + " passage");
                    assertEquals(expected.start, start, kind + " row " + row + " start");
                    assertEquals(expected.elapsed, elapsed, kind + " row " + row + " elapsed");
                    assertEquals(expected.valid, valid, kind + " row " + row + " valid");
                    assertEquals(oracle.rank(expected), rank, kind + " row " + row + " rank");
                    visited[0]++;
                });

        assertEquals(oracle.rows.size(), visited[0], kind.name());
        assertEquals(oracle.mutationRevision, marker, kind.name());

        for (int i = 0; i < 80; i++) {
            int id = id(i);
            assertEquals(oracle.validCount(id), actual.validCount(id), kind + " validCount " + i);
            assertEquals(oracle.retainedCount(id), actual.retainedCount(id), kind + " retainedCount " + i);

            Long expectedStart = oracle.starts.get(id);
            assertEquals(
                    expectedStart == null ? RegistrationList.NO_TIME : expectedStart.longValue(),
                    actual.startTime(id),
                    kind + " start " + i);
        }

        // Exercise the per-registration visitor used by START_CHANGED sync.
        int sampleId = id(17);
        final int[] idRows = {0};
        actual.visitRegistration(sampleId, (row, seq, id, passage, start, elapsed, valid, rank) -> {
            assertEquals(sampleId, id);
            idRows[0]++;
        });
        assertEquals(oracle.retainedCount(sampleId), idRows[0], kind.name());
    }

    private static long elapsed(long start, long passage) {
        if (start == RegistrationList.NO_TIME || passage < start)
            return RegistrationList.NO_TIME;
        return passage - start;
    }

    private static int id(int number) {
        return ID_BASE | (number + 1);
    }
}
