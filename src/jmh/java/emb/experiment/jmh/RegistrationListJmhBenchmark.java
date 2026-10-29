package emb.experiment.jmh;

import emb.experiment.active.RegistrationList;
import emb.experiment.active.RegistrationMutations;
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

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
public class RegistrationListJmhBenchmark {
    private static final long BASE_TIME = 1_700_000_000_000L;
    private static final long BASE_START =
            emb.experiment.active.StageTiming.compactUtc(Instant.ofEpochMilli(BASE_TIME));
    private static final long CHANGED_START =
            emb.experiment.active.StageTiming.compactUtc(Instant.ofEpochMilli(BASE_TIME + 500L));

    private static final class RowChecksum implements RegistrationList.Visitor {
        long value;

        void reset() {
            value = 0;
        }

        @Override
        public void accept(
                int row,
                long sequence,
                int registrationId,
                long passageTime,
                long startTime,
                long elapsedTime,
                boolean valid,
                int rank) {

            value += row * 7L
                    + sequence * 11L
                    + registrationId * 13L
                    + passageTime
                    + startTime
                    + elapsedTime
                    + rank * 17L
                    + (valid ? 1L : 0L);
        }
    }

    private static final class MutationChecksum implements RegistrationMutations.Visitor {
        long value;

        void reset() {
            value = 0;
        }

        @Override
        public void accept(
                long revision,
                RegistrationMutations.Kind kind,
                int subject,
                int flags) {

            value += revision * 7L + kind.ordinal() * 11L + subject * 13L + flags;
        }
    }

    @State(Scope.Thread)
    public static class ListState {
        @Param({"REBUILD_SORT", "SORTED_ARRAY", "TREE_MAP", "POOLED_AVL"})
        public String implementation;

        RegistrationList list;
        RowChecksum rows;
        MutationChecksum mutations;

        @Setup(Level.Trial)
        public void setup() {
            list = new RegistrationList(
                    1024,
                    1024,
                    1024,
                    RankingIndexKind.valueOf(implementation).create(512));

            list.beginInitialLoad();
            for (int i = 0; i < 500; i++)
                list.add(i + 1L, registrationId(i), passage(i), BASE_START);
            list.finishInitialLoad();

            // Build a stable 64-entry live mutation window for mutationBatch64.
            for (int i = 0; i < 64; i++)
                list.setStart(registrationId(i), stageStart(100L + i * 10L));

            rows = new RowChecksum();
            mutations = new MutationChecksum();
        }
    }

    @State(Scope.Thread)
    public static class MultipleRowsState {
        @Param({"REBUILD_SORT", "SORTED_ARRAY", "TREE_MAP", "POOLED_AVL"})
        public String implementation;

        RegistrationList list;
        RowChecksum rows;

        @Setup(Level.Trial)
        public void setup() {
            list = new RegistrationList(
                    1024,
                    1024,
                    1024,
                    RankingIndexKind.valueOf(implementation).create(512));

            list.beginInitialLoad();
            for (int i = 0; i < 500; i++)
                list.setStart(registrationId(i), BASE_START);
            for (int i = 0; i < 500; i++)
                list.add(i + 1L, registrationId(i), passage(i));

            // Registration 400 deliberately has two retained rows.
            list.add(
                    501L,
                    registrationId(400),
                    passage(400) + 10_000L,
                    BASE_START);
            list.finishInitialLoad();

            rows = new RowChecksum();
        }
    }

    @Benchmark
    public long snapshot500(ListState state) {
        state.rows.reset();
        long marker = state.list.visitSnapshot(state.rows);
        return state.rows.value ^ marker;
    }

    @Benchmark
    public long directRowSync(ListState state) {
        state.rows.reset();
        state.list.visitRow(250, state.rows);
        return state.rows.value;
    }

    @Benchmark
    public long registrationSyncSingle(ListState state) {
        state.rows.reset();
        int visited = state.list.visitRegistration(registrationId(400), state.rows);
        return state.rows.value + visited;
    }

    @Benchmark
    public long registrationSyncMultiple(MultipleRowsState state) {
        state.rows.reset();
        int visited = state.list.visitRegistration(registrationId(400), state.rows);
        return state.rows.value + visited;
    }

    @Benchmark
    public long mutationBatch64(ListState state) {
        state.mutations.reset();
        int visited = state.list.visitMutationsAfter(0, 64, state.mutations);
        return state.mutations.value + visited;
    }

    /**
     * Changes one start, reads the affected registration, then restores the
     * original start and reads it again. The logical state is restored each time.
     */
    @Benchmark
    public long startChangeAndSyncRoundTrip(ListState state) {
        int id = registrationId(450);

        if (!state.list.setStart(id, CHANGED_START))
            throw new IllegalStateException("start should change");

        state.rows.reset();
        int changedVisited = state.list.visitRegistration(id, state.rows);
        long checksum = state.rows.value + changedVisited;

        if (!state.list.setStart(id, BASE_START))
            throw new IllegalStateException("start should restore");

        state.rows.reset();
        int restoredVisited = state.list.visitRegistration(id, state.rows);
        checksum += state.rows.value + restoredVisited;

        return checksum;
    }

    @Benchmark
    public long startChangeMultipleRoundTrip(MultipleRowsState state) {
        int id = registrationId(400);

        if (!state.list.setStart(id, CHANGED_START))
            throw new IllegalStateException("start should change");

        state.rows.reset();
        int changedVisited = state.list.visitRegistration(id, state.rows);
        long checksum = state.rows.value + changedVisited;

        if (!state.list.setStart(id, BASE_START))
            throw new IllegalStateException("start should restore");

        state.rows.reset();
        int restoredVisited = state.list.visitRegistration(id, state.rows);
        checksum += state.rows.value + restoredVisited;

        return checksum;
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
}
