package emb.experiment.registration;

/**
 * One fresh JVM per invocation, to be timed by an external process timer.
 * Includes process launch, class loading, first-use constructor and filling
 * a population. Never claim this is pure JVM startup or per-row processing time.
 */
public final class StartupProbe {
    public static void main(String[] args) {
        if (args.length != 2)
            throw new IllegalArgumentException("usage: StartupProbe <PRODUCTION_EAGER|REBUILD|SORTED_ARRAY|PACKED_SORTED> <population>");
        ModelKind mode = ModelKind.valueOf(args[0]);
        int population = Integer.parseInt(args[1]);
        if (population < 1) throw new IllegalArgumentException("population must be positive");

        long processStartedInMain = System.nanoTime();
        ProjectionModel p = mode.create(512);
        for (int id = 0; id < 128; id++) p.setStart(id, 1_700_000_000_000L);
        for (int i = 1; i <= population; i++)
            p.add(i, i % 128, 1_700_000_060_000L + i % 201 * 1000L);

        final long[] checksum = new long[1];
        p.visitAfter(population / 2, 32, (seq, id, time, elapsed, valid, rank) ->
                checksum[0] += seq + id * 31L + elapsed + rank + (valid ? 1 : 0));
        long mainNs = System.nanoTime() - processStartedInMain;
        System.out.println("mode=" + mode
                + ";rows=" + p.size()
                + ";rankable=" + p.rankableCount()
                + ";rowGrowthEvents=" + p.rowGrowthEvents()
                + ";rankGrowthEvents=" + p.rankGrowthEvents()
                + ";mainToFirstPageNs=" + mainNs
                + ";checksum=" + checksum[0]);
    }

    private StartupProbe() {}
}
