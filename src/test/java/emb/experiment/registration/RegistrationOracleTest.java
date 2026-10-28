package emb.experiment.registration;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Oracle is intentionally independent of both production candidates:
 * it neither maintains a rank index nor reads a candidate's computed rank.
 * A result is recomputed by counting strictly faster valid rows.
 */
final class RegistrationOracleTest {
    private static final class Oracle {
        private static final class Entry {
            final long sequence;
            final int registrationId;
            final long passage;
            boolean valid = true;

            Entry(long sequence, int registrationId, long passage) {
                this.sequence = sequence;
                this.registrationId = registrationId;
                this.passage = passage;
            }
        }

        private final List<Entry> rows = new ArrayList<Entry>();
        private final Map<Integer, Long> starts = new HashMap<Integer, Long>();
        private long stateRevision;
        private long rankingRevision;
        private long mutationSequence;
        private long revokeSequence;

        void add(long sequence, int id, long time) {
            Entry entry = new Entry(sequence, id, time);
            rows.add(entry);
            mutationSequence = sequence;
            if (elapsed(entry) != RegistrationProjection.MISSING) rankingRevision++;
            stateRevision++;
        }

        boolean revoke(long sequence, int id, long time) {
            for (int i = rows.size() - 1; i >= 0; i--) {
                Entry entry = rows.get(i);
                if (entry.valid && entry.registrationId == id && entry.passage == time) {
                    boolean ranked = elapsed(entry) != RegistrationProjection.MISSING;
                    entry.valid = false;
                    if (ranked) rankingRevision++;
                    stateRevision++;
                    mutationSequence = sequence;
                    revokeSequence = sequence;
                    return true;
                }
            }
            return false;
        }

        boolean setStart(int id, long time) {
            Long old = starts.put(id, time);
            if (old != null && old.longValue() == time) return false;
            boolean changed = false;
            for (Entry entry : rows) {
                if (entry.registrationId == id && entry.valid) {
                    long previous = old == null || entry.passage < old.longValue()
                            ? RegistrationProjection.MISSING : entry.passage - old.longValue();
                    if (previous != elapsed(entry)) changed = true;
                }
            }
            if (changed) rankingRevision++;
            stateRevision++;
            return true;
        }

        boolean deleteStart(int id) {
            Long old = starts.remove(id);
            if (old == null) return false;
            boolean changed = false;
            for (Entry entry : rows) {
                if (entry.registrationId == id && entry.valid &&
                        entry.passage >= old.longValue()) changed = true;
            }
            if (changed) rankingRevision++;
            stateRevision++;
            return true;
        }

        long elapsed(Entry entry) {
            Long start = starts.get(entry.registrationId);
            return start == null || entry.passage < start.longValue()
                    ? RegistrationProjection.MISSING : entry.passage - start.longValue();
        }

        int rankableCount() {
            int count = 0;
            for (Entry row : rows) if (row.valid && elapsed(row) != RegistrationProjection.MISSING) count++;
            return count;
        }

        List<String> snapshot(long afterSequence, int limit) {
            List<String> result = new ArrayList<String>();
            for (Entry current : rows) {
                if (current.sequence <= afterSequence) continue;
                if (result.size() >= limit) break;
                long elapsed = elapsed(current);
                int rank = 0;
                if (current.valid && elapsed != RegistrationProjection.MISSING) {
                    rank = 1;
                    for (Entry other : rows) {
                        if (other.valid && elapsed(other) != RegistrationProjection.MISSING
                                && elapsed(other) < elapsed) rank++;
                    }
                }
                result.add(current.sequence + ":" + current.registrationId + ":" + current.passage
                        + ":" + elapsed + ":" + current.valid + ":" + rank);
            }
            return result;
        }
    }

    private static void assertEquivalent(Oracle oracle, RegistrationProjection projection,
                                          long after, int limit) {
        List<String> actual = new ArrayList<String>();
        int returned = projection.visitAfter(after, limit, (sequence, id, passage, elapsed, valid, rank) ->
                actual.add(sequence + ":" + id + ":" + passage + ":" + elapsed + ":" + valid + ":" + rank));
        assertEquals(oracle.snapshot(after, limit), actual);
        assertEquals(actual.size(), returned);
        assertEquals(oracle.rows.size(), projection.size());
        assertEquals(oracle.rankableCount(), projection.rankableCount());
        assertEquals(oracle.stateRevision, projection.stateRevision());
        assertEquals(oracle.rankingRevision, projection.rankingRevision());
        assertEquals(oracle.mutationSequence, projection.lastMutationSequence());
        assertEquals(oracle.revokeSequence, projection.lastRevokeSequence());
    }

    @Test void seededOracleWithStageStartChangesRevokesAndDisplayPaging() {
        for (RegistrationProjection.RankingMode mode : RegistrationProjection.RankingMode.values()) {
            RegistrationProjection projection = new RegistrationProjection(mode, 16);
            Oracle oracle = new Oracle();
            Random random = new Random(0x010010L);
            long sequence = 0L;
            final int operations = 1550;
            for (int op = 0; op < operations; op++) {
                int id = random.nextInt(60);
                long passage = 1_100_000L + random.nextInt(20) * 1000L;
                int action = random.nextInt(10);
                if (action < 6) {
                    sequence++;
                    oracle.add(sequence, id, passage);
                    projection.add(sequence, id, passage);
                } else if (action < 8) {
                    sequence++;
                    assertEquals(oracle.revoke(sequence, id, passage),
                            projection.revoke(sequence, id, passage), "REV at op " + op);
                } else if (action == 8) {
                    long start = 1_098_000L + random.nextInt(25) * 1000L;
                    assertEquals(oracle.setStart(id, start),
                            projection.setStart(id, start));
                } else {
                    assertEquals(oracle.deleteStart(id), projection.deleteStart(id));
                }

                if (op < 120 || (op % 32 == 0) || op == operations - 1) {
                    assertEquivalent(oracle, projection, 0, Integer.MAX_VALUE);
                    long after = random.nextInt((int) sequence + 1);
                    assertEquivalent(oracle, projection, after, 13);
                    assertEquivalent(oracle, projection, after, 0);
                }
            }
            assertTrue(oracle.rows.size() > 512);
            assertTrue(projection.rowGrowthEvents() > 0);
        }
    }

    @Test void exactCapacityGrowthAndFiveThousandEntries() {
        for (RegistrationProjection.RankingMode mode : RegistrationProjection.RankingMode.values()) {
            RegistrationProjection projection = new RegistrationProjection(mode, 512);
            Oracle oracle = new Oracle();
            for (int id = 0; id < 20; id++) {
                long start = 1000L + id;
                assertTrue(projection.setStart(id, start));
                assertTrue(oracle.setStart(id, start));
            }
            for (int i = 1; i <= 5000; i++) {
                int id = i % 20;
                long passage = 10_000L + (i % 13) * 100L;
                projection.add(i, id, passage);
                oracle.add(i, id, passage);
                if (i == 512) {
                    assertEquals(512, projection.rowCapacity());
                    assertEquals(0, projection.rowGrowthEvents());
                } else if (i == 513) {
                    assertEquals(1024, projection.rowCapacity());
                    assertEquals(1, projection.rowGrowthEvents());
                }
            }
            assertEquivalent(oracle, projection, 0, 30);
            assertEquivalent(oracle, projection, 4990, 30);
            assertEquals(5000, projection.rankableCount());
            assertEquals(5000, projection.size());
        }
    }

    @Test void repeatedIdenticalAddRevTargetsNewestValidPassage() {
        for (RegistrationProjection.RankingMode mode : RegistrationProjection.RankingMode.values()) {
            RegistrationProjection projection = new RegistrationProjection(mode, 1);
            Oracle oracle = new Oracle();
            projection.setStart(8, 100);
            oracle.setStart(8, 100);
            for (int i = 1; i <= 3; i++) {
                projection.add(i, 8, 200);
                oracle.add(i, 8, 200);
            }
            for (int i = 4; i <= 6; i++) {
                assertTrue(projection.revoke(i, 8, 200));
                assertTrue(oracle.revoke(i, 8, 200));
                assertEquivalent(oracle, projection, 0, 10);
                assertEquals(6 - i, projection.rankableCount());
            }
            assertFalse(projection.revoke(7, 8, 200));
            assertFalse(oracle.revoke(7, 8, 200));
            assertEquivalent(oracle, projection, 0, 10);
        }
    }
}
