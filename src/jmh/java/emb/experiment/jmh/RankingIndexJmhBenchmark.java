package emb.experiment.jmh;

import emb.experiment.ranking.RankingIndex;
import emb.experiment.ranking.RankingIndexKind;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
public class RankingIndexJmhBenchmark {
    @State(Scope.Thread)
    public static class RankingState {
        @Param({"REBUILD_SORT", "SORTED_ARRAY", "TREE_MAP", "POOLED_AVL"})
        public String implementation;

        RankingIndex ranking;
        long[] values;
        int cursor;

        @Setup(Level.Trial)
        public void setup() {
            ranking = RankingIndexKind.valueOf(implementation).create(512);
            values = new long[500];

            for (int i = 0; i < values.length; i++) {
                long value = (i % 250) * 1000L;
                values[i] = value;
                ranking.add(value);
            }
            ranking.prepare();
        }
    }

    @Benchmark
    public int rankQuery(RankingState state) {
        int index = state.cursor++;
        if (index >= state.values.length) {
            index = 0;
            state.cursor = 1;
        }
        return state.ranking.rankOf(state.values[index]);
    }

    /**
     * Mutates one value and restores it in the same invocation.
     * The RankingIndex therefore starts every invocation with the same contents.
     */
    @Benchmark
    public int replacePrepareRankRoundTrip(RankingState state) {
        int index = state.cursor++;
        if (index >= state.values.length) {
            index = 0;
            state.cursor = 1;
        }

        long oldValue = state.values[index];
        long temporaryValue = 1_000_000L + index;

        if (!state.ranking.remove(oldValue))
            throw new IllegalStateException("old ranking value missing");

        state.ranking.add(temporaryValue);
        state.ranking.prepare();
        int temporaryRank = state.ranking.rankOf(temporaryValue);

        if (!state.ranking.remove(temporaryValue))
            throw new IllegalStateException("temporary ranking value missing");

        state.ranking.add(oldValue);
        state.ranking.prepare();
        int restoredRank = state.ranking.rankOf(oldValue);

        return temporaryRank + restoredRank;
    }
}
