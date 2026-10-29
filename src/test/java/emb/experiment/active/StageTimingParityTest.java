package emb.experiment.active;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

final class StageTimingParityTest {
    @Test
    void timeOnlyConversionPreservesMissingDateAcrossUtcDay() {
        assertEquals(
                23_00_00_00L,
                StageTiming.compactUtc(
                        LocalTime.of(1, 0),
                        ZoneOffset.ofHours(2),
                        Instant.parse("2026-10-23T12:00:00Z")));
    }

    @Test
    void datedLocalConversionCarriesUtcDateAcrossPreviousDay() {
        assertEquals(
                2026_10_22_23_00_00_00L,
                StageTiming.compactUtc(
                        LocalDateTime.of(
                                LocalDate.of(2026, 10, 23),
                                LocalTime.of(1, 0)),
                        ZoneOffset.ofHours(2)));
    }

    @Test
    void absoluteConversionKeepsUtcDateAndTruncatesToCentiseconds() {
        assertEquals(
                2026_10_23_12_34_56_78L,
                StageTiming.compactUtc(
                        Instant.parse("2026-10-23T12:34:56.789Z")));
    }

    @Test
    void localDateTimeRejectsDstGapAndOverlap() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StageTiming.compactUtc(
                        LocalDateTime.of(2026, 3, 29, 2, 30),
                        ZoneId.of("Europe/Amsterdam")));

        assertThrows(
                IllegalArgumentException.class,
                () -> StageTiming.compactUtc(
                        LocalDateTime.of(2026, 10, 25, 2, 30),
                        ZoneId.of("Europe/Amsterdam")));
    }

    @Test
    void timeOnlyRejectsVariableOffsetZoneNearTransition() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StageTiming.compactUtc(
                        LocalTime.of(1, 30),
                        ZoneId.of("Europe/Amsterdam"),
                        Instant.parse("2026-10-25T00:30:00Z")));
    }

    @Test
    void timeOnlyElapsedCrossesMidnightWithoutAddingDate() {
        long passage = Instant.parse("2026-10-24T00:30:00Z").toEpochMilli();

        assertEquals(
                40L * 60L * 1000L,
                StageTiming.elapsedMillisOrMissing(
                        23_50_00_00L,
                        passage));
    }

    @Test
    void timeOnlyElapsedRejectsExactlyTwelveHoursAndLonger() {
        long passage = Instant.parse("2026-10-24T12:00:00Z").toEpochMilli();

        assertEquals(
                StageTiming.NO_ELAPSED_MILLIS,
                StageTiming.elapsedMillisOrMissing(00_00_00_00L, passage));

        assertEquals(
                StageTiming.NO_ELAPSED_MILLIS,
                StageTiming.elapsedMillisOrMissing(23_00_00_00L, passage));
    }

    @Test
    void datedElapsedUsesExplicitDateAcrossYearBoundary() {
        long start = 2025_12_31_23_59_50_00L;
        long passage = Instant.parse("2026-01-01T00:00:10Z").toEpochMilli();

        assertEquals(
                20_000L,
                StageTiming.elapsedMillisOrMissing(start, passage));
    }

    @Test
    void datedCompactElapsedMatchesJavaTimeAcrossLeapCenturyCases() {
        int[] years = {1970, 1999, 2000, 2001, 2024, 2100, 2400};
        int[] months = {1, 2, 3, 6, 12};

        for (int year : years) {
            for (int month : months) {
                LocalDate first = LocalDate.of(year, month, 1);
                LocalDate last = first.withDayOfMonth(first.lengthOfMonth());

                for (LocalDate date : new LocalDate[] {first, last}) {
                    LocalDateTime start = LocalDateTime.of(
                            date,
                            LocalTime.of(12, 34, 56, 780_000_000));
                    long compact = StageTiming.compactUtc(
                            start,
                            ZoneOffset.UTC);
                    long startEpoch = start.toInstant(ZoneOffset.UTC).toEpochMilli();
                    long passage = startEpoch + 12_340L;

                    assertEquals(
                            12_340L,
                            StageTiming.elapsedMillisOrMissing(compact, passage),
                            date.toString());
                }
            }
        }
    }

    @Test
    void invalidDatedCompactValuesAreRejectedInElapsedPath() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StageTiming.elapsedMillisOrMissing(
                        2026_02_30_12_00_00_00L,
                        Instant.parse("2026-03-01T12:00:00Z").toEpochMilli()));

        assertThrows(
                IllegalArgumentException.class,
                () -> StageTiming.elapsedMillisOrMissing(
                        2026_13_01_12_00_00_00L,
                        Instant.parse("2026-03-01T12:00:00Z").toEpochMilli()));
    }

    @Test
    void everyMinutePreservesKnownElapsedBelowTwelveHoursAcrossRollover() {
        long[] elapsedValues = {
                0L,
                10L,
                1000L,
                60_000L,
                3_600_000L,
                43_199_990L
        };

        long dayStart = Instant.parse("2026-10-24T00:00:00Z").toEpochMilli();

        for (int minute = 0; minute < 1440; minute++) {
            long passage = dayStart + minute * 60_000L;

            for (long expected : elapsedValues) {
                long startEpoch = passage - expected;
                LocalTime startTime = Instant.ofEpochMilli(startEpoch)
                        .atOffset(ZoneOffset.UTC)
                        .toLocalTime();

                long compactStart = startTime.getHour() * 1_000_000L
                        + startTime.getMinute() * 10_000L
                        + startTime.getSecond() * 100L
                        + startTime.getNano() / 10_000_000L;

                assertEquals(
                        expected,
                        StageTiming.elapsedMillisOrMissing(compactStart, passage),
                        "minute=" + minute + ", elapsed=" + expected);
            }
        }
    }
}
