package emb.experiment.ranking;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Small diagnostic benchmark for comparing the four ranking data structures.
 *
 * This is deliberately simpler than JMH. It is useful for early trends and for
 * running the exact same workload on a Pi Zero later. Architecture decisions
 * still require repeated/forked measurements.
 */
public final class RankingBenchmark {
    private static final int INITIAL_CAPACITY = 512;
    private static final int WARMUPS = 3;
    private static final int SAMPLES = 5;
    private static final int REPLACEMENTS = 1024;
    private static final int QUERIES = 2048;

    private static volatile long blackhole;

    private static final class Replacement {
        final long oldValue;
        final long newValue;

        Replacement(long oldValue, long newValue) {
            this.oldValue = oldValue;
            this.newValue = newValue;
        }
    }

    private static final class Meter {
        private final ThreadMXBean bean;
        private final long threadId = Thread.currentThread().getId();

        Meter() {
            java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
            ThreadMXBean candidate = raw instanceof ThreadMXBean ? (ThreadMXBean) raw : null;

            if (candidate != null && candidate.isThreadAllocatedMemorySupported()) {
                if (!candidate.isThreadAllocatedMemoryEnabled())
                    candidate.setThreadAllocatedMemoryEnabled(true);
            } else {
                candidate = null;
            }

            bean = candidate;
        }

        long allocatedBytes() {
            return bean == null ? -1L : bean.getThreadAllocatedBytes(threadId);
        }
    }

    private static final class Measurement {
        final long nanos;
        final long allocatedBytes;
        final long checksum;
        final int capacity;
        final int growthEvents;

        Measurement(long nanos, long allocatedBytes, long checksum,
                    int capacity, int growthEvents) {
            this.nanos = nanos;
            this.allocatedBytes = allocatedBytes;
            this.checksum = checksum;
            this.capacity = capacity;
            this.growthEvents = growthEvents;
        }
    }

    private interface Work {
        Measurement run();
    }

    public static void main(String[] args) {
        int[] populations = args.length == 0
                ? new int[] {500, 1000, 5000}
                : new int[] {Integer.parseInt(args[0])};

        Meter meter = new Meter();

        System.out.println("# Diagnostic benchmark: 3 warmups, 5 measured samples, median");
        System.out.println("# JVM=" + System.getProperty("java.vm.name")
                + ";Java=" + System.getProperty("java.version")
                + ";cores=" + Runtime.getRuntime().availableProcessors());
        System.out.println(
                "scenario,solution,population,operations,median_ns_per_operation,"
                + "median_allocated_bytes_per_operation,capacity,growth_events,checksum");

        for (RankingKind kind : RankingKind.values()) {
            report("construct_512", kind, 0, 100, () -> construct(kind, meter));

            if (kind != RankingKind.TREE_MAP)
                report("grow_512_to_513", kind, 513, 1, () -> grow(kind, meter));

            for (int population : populations) {
                long[] initial = initialValues(population);
                Replacement[] replacements = replacementWorkload(initial);
                long[] queries = queryWorkload(initial);

                report("initial_fill", kind, population, population,
                        () -> fill(kind, initial, meter));

                report("replace_and_prepare", kind, population, replacements.length,
                        () -> replace(kind, initial, replacements, false, meter));

                report("replace_prepare_and_rank", kind, population, replacements.length,
                        () -> replace(kind, initial, replacements, true, meter));

                report("rank_query", kind, population, queries.length,
                        () -> query(kind, initial, queries, meter));
            }
        }

        System.out.println("# blackhole=" + blackhole);
    }

    private static Measurement construct(RankingKind kind, Meter meter) {
        long bytesBefore = meter.allocatedBytes();
        long before = System.nanoTime();

        long checksum = 0;
        for (int i = 0; i < 100; i++) {
            Ranking ranking = kind.create(INITIAL_CAPACITY);
            checksum += ranking.size();
            checksum += ranking.capacity();
        }

        return measured(before, bytesBefore, checksum, -1, -1, meter);
    }

    private static Measurement grow(RankingKind kind, Meter meter) {
        Ranking ranking = kind.create(INITIAL_CAPACITY);

        for (int i = 0; i < INITIAL_CAPACITY; i++)
            ranking.add(i * 10L);
        ranking.prepare();

        long bytesBefore = meter.allocatedBytes();
        long before = System.nanoTime();

        ranking.add(INITIAL_CAPACITY * 10L);
        ranking.prepare();

        return measured(
                before,
                bytesBefore,
                ranking.rankOf(INITIAL_CAPACITY * 10L),
                ranking.capacity(),
                ranking.growthEvents(),
                meter);
    }

    private static Measurement fill(RankingKind kind, long[] initial, Meter meter) {
        long bytesBefore = meter.allocatedBytes();
        long before = System.nanoTime();

        Ranking ranking = kind.create(INITIAL_CAPACITY);
        for (long value : initial)
            ranking.add(value);
        ranking.prepare();

        long checksum = ranking.size() * 31L + ranking.distinctSize();

        return measured(
                before,
                bytesBefore,
                checksum,
                ranking.capacity(),
                ranking.growthEvents(),
                meter);
    }

    private static Measurement replace(
            RankingKind kind,
            long[] initial,
            Replacement[] replacements,
            boolean queryRank,
            Meter meter) {

        Ranking ranking = filled(kind, initial);

        long bytesBefore = meter.allocatedBytes();
        long before = System.nanoTime();
        long checksum = 0;

        for (Replacement replacement : replacements) {
            if (!ranking.remove(replacement.oldValue))
                throw new IllegalStateException("benchmark attempted to remove missing value");

            ranking.add(replacement.newValue);
            ranking.prepare();

            if (queryRank)
                checksum += ranking.rankOf(replacement.newValue);
        }

        checksum += ranking.size();

        return measured(
                before,
                bytesBefore,
                checksum,
                ranking.capacity(),
                ranking.growthEvents(),
                meter);
    }

    private static Measurement query(
            RankingKind kind,
            long[] initial,
            long[] queries,
            Meter meter) {

        Ranking ranking = filled(kind, initial);

        long bytesBefore = meter.allocatedBytes();
        long before = System.nanoTime();
        long checksum = 0;

        for (long query : queries)
            checksum += ranking.rankOf(query);

        return measured(
                before,
                bytesBefore,
                checksum,
                ranking.capacity(),
                ranking.growthEvents(),
                meter);
    }

    private static Ranking filled(RankingKind kind, long[] values) {
        Ranking ranking = kind.create(INITIAL_CAPACITY);
        for (long value : values)
            ranking.add(value);
        ranking.prepare();
        return ranking;
    }

    private static Measurement measured(
            long before,
            long bytesBefore,
            long checksum,
            int capacity,
            int growthEvents,
            Meter meter) {

        long nanos = System.nanoTime() - before;
        long bytesAfter = meter.allocatedBytes();
        long allocated = bytesBefore >= 0 && bytesAfter >= 0
                ? bytesAfter - bytesBefore
                : -1L;

        blackhole ^= checksum;
        return new Measurement(nanos, allocated, checksum, capacity, growthEvents);
    }

    private static void report(
            String scenario,
            RankingKind kind,
            int population,
            int operations,
            Work work) {

        Measurement[] all = new Measurement[WARMUPS + SAMPLES];
        for (int i = 0; i < all.length; i++)
            all[i] = work.run();

        long[] times = new long[SAMPLES];
        long[] bytes = new long[SAMPLES];
        long checksum = all[WARMUPS].checksum;

        for (int i = 0; i < SAMPLES; i++) {
            Measurement measurement = all[WARMUPS + i];
            if (measurement.checksum != checksum)
                throw new IllegalStateException("non-deterministic benchmark result");

            times[i] = measurement.nanos;
            bytes[i] = measurement.allocatedBytes;
        }

        Arrays.sort(times);
        Arrays.sort(bytes);

        Measurement last = all[all.length - 1];
        double nsPerOperation = (double) times[SAMPLES / 2] / operations;

        String allocated = bytes[SAMPLES / 2] < 0
                ? "unavailable"
                : String.format(Locale.ROOT, "%.3f",
                        (double) bytes[SAMPLES / 2] / operations);

        System.out.printf(
                Locale.ROOT,
                "%s,%s,%d,%d,%.1f,%s,%d,%d,%d%n",
                scenario,
                kind,
                population,
                operations,
                nsPerOperation,
                allocated,
                last.capacity,
                last.growthEvents,
                checksum);
    }

    private static long[] initialValues(int population) {
        long[] values = new long[population];
        int distinctRange = Math.max(8, population / 2);

        for (int i = 0; i < population; i++)
            values[i] = ((i * 37L) % distinctRange) * 10L;

        return values;
    }

    /**
     * Generate valid replacements outside the timed section.
     * Each replacement models one elapsed-time change as remove(old)+add(new).
     */
    private static Replacement[] replacementWorkload(long[] initial) {
        long[] current = Arrays.copyOf(initial, initial.length);
        Replacement[] result = new Replacement[REPLACEMENTS];
        Random random = new Random(0x2026010L);

        for (int i = 0; i < result.length; i++) {
            int index = random.nextInt(current.length);
            long oldValue = current[index];
            long newValue = 1_000_000L + i * 17L;

            result[i] = new Replacement(oldValue, newValue);
            current[index] = newValue;
        }

        return result;
    }

    private static long[] queryWorkload(long[] initial) {
        long[] result = new long[QUERIES];
        Random random = new Random(0x010010L);

        for (int i = 0; i < result.length; i++)
            result[i] = initial[random.nextInt(initial.length)];

        return result;
    }

    private RankingBenchmark() {
    }
}
