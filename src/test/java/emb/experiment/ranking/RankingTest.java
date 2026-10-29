package emb.experiment.ranking;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

final class RankingTest {
    @Test
    void competitionRankingAndDuplicatesAreIdenticalForEverySolution() {
        for (RankingKind kind : RankingKind.values()) {
            Ranking ranking = kind.create(8);

            ranking.add(10);
            ranking.add(10);
            ranking.add(15);
            ranking.add(18);
            ranking.prepare();

            assertEquals(4, ranking.size(), kind.name());
            assertEquals(3, ranking.distinctSize(), kind.name());
            assertEquals(1, ranking.rankOf(10), kind.name());
            assertEquals(3, ranking.rankOf(15), kind.name());
            assertEquals(4, ranking.rankOf(18), kind.name());
            assertEquals(0, ranking.rankOf(12), kind.name());

            assertTrue(ranking.remove(10), kind.name());
            ranking.prepare();
            assertEquals(1, ranking.rankOf(10), kind.name());
            assertEquals(2, ranking.rankOf(15), kind.name());
            assertEquals(3, ranking.rankOf(18), kind.name());

            assertTrue(ranking.remove(10), kind.name());
            ranking.prepare();
            assertEquals(0, ranking.rankOf(10), kind.name());
            assertEquals(1, ranking.rankOf(15), kind.name());
            assertEquals(2, ranking.rankOf(18), kind.name());
            assertFalse(ranking.remove(10), kind.name());
        }
    }

    @Test
    void randomOperationsMatchIndependentListOracle() {
        for (RankingKind kind : RankingKind.values()) {
            Ranking ranking = kind.create(16);
            List<Long> oracle = new ArrayList<Long>();
            Random random = new Random(0x2026010L);

            for (int operation = 0; operation < 5000; operation++) {
                if (oracle.isEmpty() || random.nextInt(10) < 6) {
                    long value = random.nextInt(400);
                    ranking.add(value);
                    oracle.add(value);
                } else {
                    long value;
                    if (random.nextInt(10) < 8) {
                        value = oracle.get(random.nextInt(oracle.size()));
                    } else {
                        value = 1000 + random.nextInt(100);
                    }

                    boolean expected = oracle.remove(Long.valueOf(value));
                    assertEquals(expected, ranking.remove(value),
                            kind + " remove at operation " + operation);
                }

                if (operation < 100 || operation % 37 == 0) {
                    ranking.prepare();
                    assertEquals(oracle.size(), ranking.size(), kind.name());
                    assertEquals(distinct(oracle), ranking.distinctSize(), kind.name());

                    for (int sample = 0; sample < 8; sample++) {
                        long value = random.nextInt(420);
                        assertEquals(rankOf(oracle, value), ranking.rankOf(value),
                                kind + " value=" + value + " operation=" + operation);
                    }
                }
            }
        }
    }

    @Test
    void explicitCapacityGrowthIsVisibleForCustomStructures() {
        for (RankingKind kind : RankingKind.values()) {
            Ranking ranking = kind.create(2);
            ranking.add(10);
            ranking.add(20);

            if (kind == RankingKind.TREE_MAP) {
                assertEquals(-1, ranking.capacity());
                assertEquals(-1, ranking.growthEvents());
                continue;
            }

            assertEquals(2, ranking.capacity(), kind.name());
            assertEquals(0, ranking.growthEvents(), kind.name());

            ranking.add(30);
            assertEquals(4, ranking.capacity(), kind.name());
            assertEquals(1, ranking.growthEvents(), kind.name());
        }
    }

    @Test
    void pooledAvlDuplicatesDoNotConsumeAdditionalNodes() {
        Ranking ranking = new PooledAvlRanking(2);
        for (int i = 0; i < 1000; i++)
            ranking.add(1234);

        assertEquals(1000, ranking.size());
        assertEquals(1, ranking.distinctSize());
        assertEquals(2, ranking.capacity());
        assertEquals(0, ranking.growthEvents());
        assertEquals(1, ranking.rankOf(1234));
    }

    @Test
    void negativeElapsedTimesAreRejected() {
        for (RankingKind kind : RankingKind.values()) {
            Ranking ranking = kind.create(8);
            assertThrows(IllegalArgumentException.class, () -> ranking.add(-1), kind.name());
            assertThrows(IllegalArgumentException.class, () -> ranking.remove(-1), kind.name());
        }
    }

    private static int rankOf(List<Long> values, long value) {
        if (!values.contains(value))
            return 0;

        int faster = 0;
        for (Long stored : values) {
            if (stored.longValue() < value)
                faster++;
        }
        return faster + 1;
    }

    private static int distinct(List<Long> values) {
        if (values.isEmpty())
            return 0;

        List<Long> copy = new ArrayList<Long>(values);
        Collections.sort(copy);
        int count = 1;
        for (int i = 1; i < copy.size(); i++) {
            if (!copy.get(i).equals(copy.get(i - 1)))
                count++;
        }
        return count;
    }
}
