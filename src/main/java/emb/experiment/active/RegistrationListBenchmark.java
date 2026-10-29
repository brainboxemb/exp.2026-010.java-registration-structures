package emb.experiment.active;

import com.sun.management.ThreadMXBean;
import emb.experiment.ranking.RankingIndex;
import emb.experiment.ranking.RankingIndexKind;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/**
 * Diagnostic benchmark for the complete RegistrationList memory/search design.
 *
 * RankingIndex remains interchangeable. This benchmark measures the surrounding
 * list/index/mutation costs that the standalone RankingBenchmark intentionally
 * excludes.
 */
public final class RegistrationListBenchmark {
    private static final long BASE_TIME = 1_700_000_000_000L;
    private static final int WARMUPS = 3;
    private static final int SAMPLES = 5;
    private static volatile long blackhole;

    private interface Work {
        Measurement run();
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
        final long bytes;
        final long checksum;

        Measurement(long nanos, long bytes, long checksum) {
            this.nanos = nanos;
            this.bytes = bytes;
            this.checksum = checksum;
        }
    }

    private static final class RowChecksum implements RegistrationList.Visitor {
        long value;

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

        @Override
        public void accept(long revision, RegistrationMutations.Kind kind, int subject, int flags) {
            value += revision * 7L + kind.ordinal() * 11L + subject * 13L + flags;
        }
    }

    public static void main(String[] args) {
        int[] populations = args.length == 0
                ? new int[] {500, 1000, 5000}
                : new int[] {Integer.parseInt(args[0])};

        Meter meter = new Meter();

        System.out.println("# RegistrationList diagnostic: 3 warmups, 5 samples, median");
        System.out.println("# JVM=" + System.getProperty("java.vm.name")
                + ";Java=" + System.getProperty("java.version")
                + ";cores=" + Runtime.getRuntime().availableProcessors());
        System.out.println(
                "scenario,ranking,population,operations,median_ns_per_operation,"
                + "median_allocated_bytes_per_operation,checksum");

        for (RankingIndexKind kind : RankingIndexKind.values()) {
            for (int population : populations) {
                report("initial_fill", kind, population, population,
                        () -> initialFill(kind, population, meter));

                report("fast_revoke", kind, population, population,
                        () -> fastRevoke(kind, population, meter));

                int duplicateIds = Math.max(1, population / 2);
                report("multi_registration_revoke_scan", kind, population, duplicateIds,
                        () -> multiRegistrationRevoke(kind, population, meter));

                int startChanges = Math.min(population, 256);
                report("start_change_scan", kind, population, startChanges,
                        () -> startChanges(kind, population, startChanges, meter));

                report("snapshot_rows", kind, population, population,
                        () -> snapshot(kind, population, meter));

                int batch = Math.min(population, 64);
                report("mutation_batch", kind, population, batch,
                        () -> mutationBatch(kind, population, batch, meter));
            }
        }

        System.out.println("# blackhole=" + blackhole);
    }

    private static Measurement initialFill(RankingIndexKind kind, int population, Meter meter) {
        long beforeBytes = meter.allocatedBytes();
        long before = System.nanoTime();

        RegistrationList list = emptyList(kind, population);
        for (int i = 0; i < population; i++)
            list.setStart(registrationId(i), BASE_TIME);
        for (int i = 0; i < population; i++)
            list.add(i + 1L, registrationId(i), passage(i));

        long checksum = list.size() * 31L
                + list.uniqueValidRegistrationCount() * 17L
                + list.latestMutationRevision();

        return measured(before, beforeBytes, checksum, meter);
    }

    private static Measurement fastRevoke(RankingIndexKind kind, int population, Meter meter) {
        RegistrationList list = filledUnique(kind, population);

        long beforeBytes = meter.allocatedBytes();
        long before = System.nanoTime();

        for (int i = 0; i < population; i++) {
            boolean revoked = list.revoke(
                    population + i + 1L,
                    registrationId(i),
                    passage(i));

            if (!revoked)
                throw new IllegalStateException("expected direct REV for id " + i);
        }

        long checksum = list.uniqueValidRegistrationCount() * 31L
                + list.fastRevokeCount() * 17L
                + list.scannedRevokeCount();

        return measured(before, beforeBytes, checksum, meter);
    }

    /**
     * Build two valid rows per id in two halves, then revoke the old first-half
     * row. This intentionally exercises the exceptional reverse scan and the
     * second scan needed to restore onlyValidRow after 2 -> 1.
     */
    private static Measurement multiRegistrationRevoke(
            RankingIndexKind kind,
            int population,
            Meter meter) {

        int ids = Math.max(1, population / 2);
        int rows = ids * 2;

        RegistrationList list = emptyList(kind, rows);
        for (int i = 0; i < ids; i++)
            list.setStart(registrationId(i), BASE_TIME);

        long sequence = 0;
        for (int i = 0; i < ids; i++)
            list.add(++sequence, registrationId(i), passage(i));
        for (int i = 0; i < ids; i++)
            list.add(++sequence, registrationId(i), passage(i) + 1_000_000L);

        long beforeBytes = meter.allocatedBytes();
        long before = System.nanoTime();

        for (int i = 0; i < ids; i++) {
            boolean revoked = list.revoke(++sequence, registrationId(i), passage(i));
            if (!revoked)
                throw new IllegalStateException("expected scanned REV for id " + i);
        }

        long checksum = list.uniqueValidRegistrationCount() * 31L
                + list.scannedRevokeCount() * 17L
                + list.fastRevokeCount();

        return measured(before, beforeBytes, checksum, meter);
    }

    private static Measurement startChanges(
            RankingIndexKind kind,
            int population,
            int changes,
            Meter meter) {

        RegistrationList list = filledUnique(kind, population);

        long beforeBytes = meter.allocatedBytes();
        long before = System.nanoTime();

        for (int i = 0; i < changes; i++) {
            if (!list.setStart(registrationId(i), BASE_TIME + 1_000L))
                throw new IllegalStateException("expected changed start");
        }

        long checksum = list.latestMutationRevision() * 31L
                + list.uniqueValidRegistrationCount();

        return measured(before, beforeBytes, checksum, meter);
    }

    private static Measurement snapshot(RankingIndexKind kind, int population, Meter meter) {
        RegistrationList list = filledUnique(kind, population);
        RowChecksum visitor = new RowChecksum();

        long beforeBytes = meter.allocatedBytes();
        long before = System.nanoTime();

        long revision = list.visitSnapshot(visitor);

        long checksum = visitor.value + revision * 31L;

        return measured(before, beforeBytes, checksum, meter);
    }

    private static Measurement mutationBatch(
            RankingIndexKind kind,
            int population,
            int batch,
            Meter meter) {

        RegistrationList list = filledUnique(kind, population);
        MutationChecksum visitor = new MutationChecksum();

        long after = list.latestMutationRevision() - batch;

        long beforeBytes = meter.allocatedBytes();
        long before = System.nanoTime();

        int visited = list.visitMutationsAfter(after, batch, visitor);
        if (visited != batch)
            throw new IllegalStateException("expected mutation batch of " + batch);

        return measured(before, beforeBytes, visitor.value + visited * 31L, meter);
    }

    private static RegistrationList filledUnique(RankingIndexKind kind, int population) {
        RegistrationList list = emptyList(kind, population);

        for (int i = 0; i < population; i++)
            list.setStart(registrationId(i), BASE_TIME);

        for (int i = 0; i < population; i++)
            list.add(i + 1L, registrationId(i), passage(i));

        return list;
    }

    private static RegistrationList emptyList(RankingIndexKind kind, int population) {
        int rowCapacity = normalCapacity(population);
        int indexCapacity = nextPowerOfTwo(Math.max(2, population * 2));
        int mutationCapacity = nextPowerOfTwo(Math.max(8, population * 2));

        RankingIndex ranking = kind.create(rowCapacity);
        return new RegistrationList(
                rowCapacity,
                indexCapacity,
                mutationCapacity,
                ranking);
    }

    private static int normalCapacity(int population) {
        int capacity = 512;
        while (capacity < population)
            capacity *= 2;
        return capacity;
    }

    private static int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value)
            result <<= 1;
        return result;
    }

    private static int registrationId(int index) {
        return ('A' << 16) | (index + 1);
    }

    private static long passage(int index) {
        return BASE_TIME + 60_000L + (index % 250) * 1_000L;
    }

    private static Measurement measured(
            long before,
            long beforeBytes,
            long checksum,
            Meter meter) {

        long nanos = System.nanoTime() - before;
        long afterBytes = meter.allocatedBytes();
        long bytes = beforeBytes >= 0 && afterBytes >= 0
                ? afterBytes - beforeBytes
                : -1L;

        blackhole ^= checksum;
        return new Measurement(nanos, bytes, checksum);
    }

    private static void report(
            String scenario,
            RankingIndexKind kind,
            int population,
            int operations,
            Work work) {

        Measurement[] measurements = new Measurement[WARMUPS + SAMPLES];
        for (int i = 0; i < measurements.length; i++)
            measurements[i] = work.run();

        long[] nanos = new long[SAMPLES];
        long[] bytes = new long[SAMPLES];
        long checksum = measurements[WARMUPS].checksum;

        for (int i = 0; i < SAMPLES; i++) {
            Measurement measurement = measurements[WARMUPS + i];
            if (measurement.checksum != checksum)
                throw new IllegalStateException("non-deterministic benchmark scenario");

            nanos[i] = measurement.nanos;
            bytes[i] = measurement.bytes;
        }

        Arrays.sort(nanos);
        Arrays.sort(bytes);

        double nanosPerOperation = (double) nanos[SAMPLES / 2] / operations;
        String allocated = bytes[SAMPLES / 2] < 0
                ? "unavailable"
                : String.format(
                        Locale.ROOT,
                        "%.3f",
                        (double) bytes[SAMPLES / 2] / operations);

        System.out.printf(
                Locale.ROOT,
                "%s,%s,%d,%d,%.1f,%s,%d%n",
                scenario,
                kind,
                population,
                operations,
                nanosPerOperation,
                allocated,
                checksum);
    }

    private RegistrationListBenchmark() {
    }
}
