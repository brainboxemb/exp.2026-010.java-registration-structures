package emb.experiment.registration;

import java.time.Instant;

/**
 * A small, readable starting point for exploring the experiment.
 *
 * Run after "mvn verify":
 *   java -cp target/classes emb.experiment.registration.ExampleWalkthrough
 *
 * This uses artificial LogBook facts. It does NOT start the real Timing Point.
 */
public final class ExampleWalkthrough {
    private static final int BIB_A0001 = ('A' << 16) | 1;
    private static final int BIB_A0002 = ('A' << 16) | 2;

    public static void main(String[] args) {
        // Step 1: Choose which implementation we want to examine.
        ModelKind implementation = ModelKind.PRODUCTION_EAGER;
        LogBookProjectionSession timingPoint = new LogBookProjectionSession(implementation, 500);

        // Step 2: Supply the known starting time for the two runners.
        long startTime = Instant.parse("2026-10-29T12:00:00Z").toEpochMilli();
        timingPoint.setStart(BIB_A0001, startTime);
        timingPoint.setStart(BIB_A0002, startTime);

        // Step 3: Imagine these facts were already committed to the LogBook.
        timingPoint.append(registration(1, 1, BIB_A0001, "2026-10-29T12:00:10Z",
                LogBookProjectionSession.Action.ADD));
        timingPoint.append(registration(2, 1, BIB_A0002, "2026-10-29T12:00:15Z",
                LogBookProjectionSession.Action.ADD));

        // Step 4: Opening WP 1 rebuilds a display view from LogBook history.
        ProjectionModel displayView = timingPoint.open(1);
        System.out.println("After OPEN:");
        printRows(displayView);

        // A display first reads all rows and remembers the current revision.
        long displayChangeCursor = displayView.stateRevision();

        // Step 5: A live REV invalidates A0001, but preserves its original ADD row.
        timingPoint.append(registration(3, 1, BIB_A0001, "2026-10-29T12:00:10Z",
                LogBookProjectionSession.Action.REV));

        System.out.println();
        System.out.println("Changes since the display snapshot:");
        displayView.visitChangesAfter(displayChangeCursor, 10,
                (revision, kind, id, mutationSequence, affectedSequence, ranksChanged) -> {
                    System.out.println("  revision=" + revision + ", action=" + kind
                            + ", bib=" + formatBib(id)
                            + ", original ADD sequence=" + affectedSequence
                            + ", ranking changed=" + ranksChanged);
                });

        System.out.println();
        System.out.println("After live REV:");
        printRows(displayView);

        // Step 6: Closing discards only the display view, not LogBook history.
        timingPoint.close();
        System.out.println();
        System.out.println("LogBook records still retained: " + timingPoint.logBookSize());
    }

    private static LogBookProjectionSession.Fact registration(
            long sequence, int location, int registrationId, String utcTime,
            LogBookProjectionSession.Action action) {
        long passageMillis = Instant.parse(utcTime).toEpochMilli();
        return new LogBookProjectionSession.Fact(
                sequence,
                location,
                LogBookProjectionSession.Kind.AUTOMATIC,
                action,
                registrationId,
                passageMillis);
    }

    private static void printRows(ProjectionModel projection) {
        projection.visitAfter(0, 10, (sequence, id, passage, elapsed, valid, rank) -> {
            String elapsedText = elapsed == RegistrationProjection.MISSING
                    ? "unknown" : elapsed + " ms";

            System.out.println("  ADD #" + sequence + ", bib=" + formatBib(id)
                    + ", valid=" + valid
                    + ", elapsed=" + elapsedText
                    + ", rank=" + rank);
        });
    }

    private static String formatBib(int packedId) {
        return String.format("%c%04d", (char) (packedId >>> 16), packedId & 0xffff);
    }

    private ExampleWalkthrough() {
    }
}
