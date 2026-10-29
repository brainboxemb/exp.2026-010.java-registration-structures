package emb.experiment.registration;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Random;

/**
 * Reproducible *diagnostic* harness. This is NOT JMH and does not establish
 * statistically robust performance superiority. Use for trend exploration,
 * capacity transition characterization and target-system smoke measurements.
 *
 * stdout is CSV; explanations and command line are in BENCHMARK.md.
 */
public final class DiagnosticBenchmark {
    private static final long START = 1_700_000_000_000L;
    private static final int INITIAL_CAPACITY = 512;
    private static final int STAGES = 128;
    private static final int OPERATIONS = 2048;
    private static final int WARMUP = 3;
    private static final int SAMPLES = 5;
    private static volatile long escape;

    private static final class Visitor implements RegistrationProjection.RowVisitor {
        long checksum;
        @Override public void accept(long seq, int id, long passage, long elapsed,
                                     boolean valid, int rank) {
            checksum += seq * 17 + id * 13 + passage + elapsed + (valid ? 1 : 0) + rank;
        }
    }

    private static final class Event {
        final int type;
        final int id;
        final long sequence;
        final long time;
        Event(int type, int id, long sequence, long time) {
            this.type = type; this.id = id; this.sequence = sequence; this.time = time;
        }
    }

    private static final class Meter {
        final ThreadMXBean bean;
        final long tid = Thread.currentThread().getId();

        Meter() {
            java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
            ThreadMXBean mx = raw instanceof ThreadMXBean ? (ThreadMXBean) raw : null;
            if (mx != null && mx.isThreadAllocatedMemorySupported()) {
                if (!mx.isThreadAllocatedMemoryEnabled()) {
                    try { mx.setThreadAllocatedMemoryEnabled(true); }
                    catch (SecurityException ignored) { mx = null; }
                }
            } else mx = null;
            bean = mx;
        }

        long allocated() {
            return bean != null ? bean.getThreadAllocatedBytes(tid) : -1L;
        }

        long delta(long start) {
            long end = allocated();
            return end >= 0 && start >= 0 ? end - start : -1L;
        }
    }

    private static final class Measurement {
        final long nanos;
        final long bytes;
        final long checksum;
        final int rowGrowth;
        final int rankGrowth;
        Measurement(long nanos, long bytes, long checksum, int rowGrowth, int rankGrowth) {
            this.nanos = nanos; this.bytes = bytes; this.checksum = checksum;
            this.rowGrowth = rowGrowth; this.rankGrowth = rankGrowth;
        }
    }

    private static void ensure(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    private static ProjectionModel initialized(ModelKind mode, int n) {
        ProjectionModel p = mode.create(INITIAL_CAPACITY);
        for (int id = 0; id < STAGES; id++) p.setStart(id, START);
        for (int i = 1; i <= n; i++) {
            int id = i % STAGES;
            p.add(i, id, START + 60_000L + (i * 37 % 201) * 1000L);
        }
        return p;
    }

    private static Event[] workload(int n) {
        Event[] result = new Event[OPERATIONS];
        Random random = new Random(0x010010L);
        long sequence = n;
        int lastId = 0;
        long lastPassage = START;
        for (int i = 0; i < result.length; i++) {
            int id = random.nextInt(STAGES);
            long passage = START + 60_000L + random.nextInt(201) * 1000L;
            int type;
            // Every 4th ADD is followed by a matching REV; others are retained.
            if (i % 8 == 1) {
                type = 1; id = lastId; passage = lastPassage;
            } else if (i % 8 == 3) {
                type = 2; passage = START + random.nextInt(120) * 1000L;
            } else if (i % 8 == 5) {
                type = 3;
            } else {
                type = 0; lastId = id; lastPassage = passage;
            }
            if (type == 0 || type == 1) sequence++;
            result[i] = new Event(type, id, sequence, passage);
        }
        return result;
    }

    private static Measurement run(ModelKind mode, int n,
                                   Event[] events, Meter meter, int pageEvery) {
        ProjectionModel p = initialized(mode, n);
        Visitor visitor = new Visitor();
        int matchedRevs = 0;
        long allocatedStart = meter.allocated();
        long before = System.nanoTime();
        for (int i = 0; i < events.length; i++) {
            Event e = events[i];
            switch (e.type) {
                case 0: p.add(e.sequence, e.id, e.time); break;
                case 1: if (p.revoke(e.sequence, e.id, e.time)) matchedRevs++; break;
                case 2: p.setStart(e.id, e.time); break;
                case 3: p.deleteStart(e.id); break;
                default: throw new AssertionError();
            }
            if (pageEvery > 0 && (i + 1) % pageEvery == 0) {
                // Query the same bounded chronological window for every mode.
                p.visitAfter(n / 2L, 32, visitor);
            }
        }
        long elapsed = System.nanoTime() - before;
        long bytes = meter.delta(allocatedStart);
        ensure(matchedRevs == OPERATIONS / 8, "unexpected missing REV: " + matchedRevs);
        long checksum = visitor.checksum + p.rankableCount() * 7L
                + p.stateRevision() * 23L + p.rankingRevision() * 29L;
        escape ^= checksum;
        return new Measurement(elapsed, bytes, checksum, p.rowGrowthEvents(), p.rankGrowthEvents());
    }

    private static Measurement fill(ModelKind mode, int n, Meter meter) {
        long startBytes = meter.allocated();
        long start = System.nanoTime();
        ProjectionModel p = initialized(mode, n);
        long nanos = System.nanoTime() - start;
        long bytes = meter.delta(startBytes);
        escape ^= p.size() + p.rankableCount();
        return new Measurement(nanos, bytes,
                p.size() * 101L + p.rankableCount(), p.rowGrowthEvents(), p.rankGrowthEvents());
    }

    private static Measurement growth(ModelKind mode, Meter meter) {
        ProjectionModel p = initialized(mode, 512);
        long startBytes = meter.allocated();
        long start = System.nanoTime();
        p.add(513L, 10, START + 120_000L);
        long nanos = System.nanoTime() - start;
        long bytes = meter.delta(startBytes);
        ensure(p.rowGrowthEvents() == 1, "missing 512->513 row growth");
        escape ^= p.size();
        return new Measurement(nanos, bytes,
                p.size() * 101L + p.rankableCount(), p.rowGrowthEvents(), p.rankGrowthEvents());
    }

    private static Measurement constructor(ModelKind mode, Meter meter) {
        final int repeat = 100;
        long sum = 0;
        long startBytes = meter.allocated();
        long start = System.nanoTime();
        for (int i = 0; i < repeat; i++) {
            ProjectionModel p = mode.create(INITIAL_CAPACITY);
            sum += p.rowCapacity() + p.rankableCount();
        }
        long nanos = System.nanoTime() - start;
        long bytes = meter.delta(startBytes);
        escape ^= sum;
        return new Measurement(nanos, bytes, sum, 0, 0);
    }

    private interface Measure { Measurement get(); }

    private static void report(String scenario, ModelKind mode,
                               int population, int operations, Measure f) {
        Measurement[] measurements = new Measurement[WARMUP + SAMPLES];
        for (int i = 0; i < measurements.length; i++) measurements[i] = f.get();
        long[] times = new long[SAMPLES];
        long[] bytes = new long[SAMPLES];
        long checksum = measurements[WARMUP].checksum;
        for (int i = 0; i < SAMPLES; i++) {
            Measurement m = measurements[WARMUP + i];
            ensure(m.checksum == checksum, "non-deterministic benchmark scenario");
            times[i] = m.nanos;
            bytes[i] = m.bytes;
        }
        Arrays.sort(times);
        Arrays.sort(bytes);
        Measurement last = measurements[measurements.length - 1];
        double nsPerOperation = (double) times[SAMPLES / 2] / operations;
        String bytesPerOperation = bytes[SAMPLES / 2] < 0 ? "unavailable"
                : Double.toString((double) bytes[SAMPLES / 2] / operations);
        System.out.printf(java.util.Locale.ROOT,
                "%s,%s,%d,%d,%.1f,%s,%d,%d,%d%n",
                scenario, mode, population, operations, nsPerOperation,
                bytesPerOperation, last.rowGrowth, last.rankGrowth, checksum);
    }

    public static void main(String[] args) {
        if (args.length != 0 && args.length != 1)
            throw new IllegalArgumentException("usage: DiagnosticBenchmark [population]");
        int[] populations = args.length == 0 ? new int[]{500, 1000, 5000}
                : new int[]{Integer.parseInt(args[0])};
        Meter meter = new Meter();
        System.out.println("# Diagnostic only; warm JVM; 3 warmups, 5 samples, median");
        System.out.println("# JVM=" + System.getProperty("java.vm.name")
                + ";Java=" + System.getProperty("java.version")
                + ";cores=" + Runtime.getRuntime().availableProcessors());
        System.out.println("scenario,mode,population,operations,median_ns_per_operation,"
                + "median_allocated_bytes_per_operation,row_growth_events,rank_growth_events,checksum");
        for (ModelKind mode : ModelKind.values()) {
            report("construct_512", mode, 0, 100, () -> constructor(mode, meter));
            report("grow_512_to_513", mode, 513, 1, () -> growth(mode, meter));
            for (int n : populations) {
                if (n < 1) throw new IllegalArgumentException("population must be > 0");
                report("initial_fill", mode, n, n, () -> fill(mode, n, meter));
                Event[] events = workload(n);
                report("mutation_only", mode, n, OPERATIONS,
                        () -> run(mode, n, events, meter, 0));
                report("page32_every_16_mutations", mode, n, OPERATIONS,
                        () -> run(mode, n, events, meter, 16));
                report("page32_every_mutation", mode, n, OPERATIONS,
                        () -> run(mode, n, events, meter, 1));
            }
        }
        System.out.println("# blackhole=" + escape);
    }

    private DiagnosticBenchmark() {}
}
