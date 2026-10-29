package emb.experiment.jmh;

import emb.experiment.active.RegistrationList;
import emb.experiment.ranking.RankingIndex;
import emb.experiment.ranking.RankingIndexKind;
import org.openjdk.jol.info.GraphLayout;

import java.time.Instant;

/**
 * Retained object-graph probe for the normal Experiment 010 population.
 *
 * This is not a throughput benchmark. It reports the total reachable heap graph
 * from each candidate after filling 500 registrations.
 */
public final class MemoryFootprintProbe {
    private static final long BASE_TIME = 1_700_000_000_000L;
    private static final long BASE_START =
            emb.experiment.active.StageTiming.compactUtc(Instant.ofEpochMilli(BASE_TIME));

    public static void main(String[] args) {
        System.out.println("scope,implementation,entries,total_bytes,total_kib");

        for (RankingIndexKind kind : RankingIndexKind.values()) {
            RankingIndex ranking = filledRanking(kind);
            print("ranking", kind.name(), ranking.size(), ranking);

            RegistrationList list = filledRegistrationList(kind);
            print("registration_list", kind.name(), list.size(), list);
        }
    }

    private static RankingIndex filledRanking(RankingIndexKind kind) {
        RankingIndex ranking = kind.create(512);

        for (int i = 0; i < 500; i++)
            ranking.add((i % 250) * 1000L);

        ranking.prepare();
        return ranking;
    }

    private static RegistrationList filledRegistrationList(RankingIndexKind kind) {
        RegistrationList list = new RegistrationList(
                1024,
                1024,
                1024,
                kind.create(512));

        list.beginInitialLoad();

        for (int i = 0; i < 500; i++)
            list.setStart(registrationId(i), BASE_START);

        for (int i = 0; i < 500; i++)
            list.add(i + 1L, registrationId(i), passage(i));

        list.finishInitialLoad();
        return list;
    }

    private static void print(String scope, String implementation, int entries, Object root) {
        long bytes = GraphLayout.parseInstance(root).totalSize();

        System.out.printf(
                java.util.Locale.ROOT,
                "%s,%s,%d,%d,%.2f%n",
                scope,
                implementation,
                entries,
                bytes,
                bytes / 1024.0);
    }

    private static long stageStart(long offsetMillis) {
        return emb.experiment.active.StageTiming.compactUtc(
                Instant.ofEpochMilli(BASE_TIME + offsetMillis));
    }

    private static int registrationId(int index) {
        return ('A' << 16) | (index + 1);
    }

    private static long passage(int index) {
        return BASE_TIME + 60_000L + (index % 250) * 1_000L;
    }

    private MemoryFootprintProbe() {
    }
}
