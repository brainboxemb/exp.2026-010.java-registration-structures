package emb.experiment.active;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;

/**
 * Production-parity compact UTC stage timing.
 *
 * Dated values use yyyyMMddHHmmsscc in UTC.
 * Time-only values use HHmmsscc in UTC and deliberately retain no date.
 */
public final class StageTiming {
    public static final long NO_ELAPSED_MILLIS = Long.MIN_VALUE;

    private static final long DATE_THRESHOLD = 100_000_000L;
    private static final long CENTISECOND_NANOS = 10_000_000L;
    private static final long DAY_MILLIS = 86_400_000L;
    private static final long HALF_DAY_MILLIS = 43_200_000L;

    private StageTiming() {
    }

    /** Convert one absolute instant to compact UTC yyyyMMddHHmmsscc. */
    public static long compactUtc(Instant instant) {
        if (instant == null)
            throw new IllegalArgumentException("instant must not be null");

        return compactDateTime(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /**
     * Convert one local date/time to compact UTC.
     * DST gaps and overlaps are rejected because the input does not identify an offset.
     */
    public static long compactUtc(LocalDateTime localDateTime, ZoneId sourceZone) {
        if (localDateTime == null)
            throw new IllegalArgumentException("localDateTime must not be null");
        if (sourceZone == null)
            throw new IllegalArgumentException("sourceZone must not be null");

        return compactUtc(localInstant(localDateTime, sourceZone));
    }

    /**
     * Convert one local time-only value to compact UTC while retaining no date.
     *
     * The zone offset active at offsetReference is used. Variable-offset zones
     * are rejected when a transition is within twelve hours of that reference.
     */
    public static long compactUtc(
            LocalTime localTime,
            ZoneId sourceZone,
            Instant offsetReference) {

        if (localTime == null)
            throw new IllegalArgumentException("localTime must not be null");
        if (sourceZone == null)
            throw new IllegalArgumentException("sourceZone must not be null");
        if (offsetReference == null)
            throw new IllegalArgumentException("offsetReference must not be null");

        ZoneOffset offset = timeOnlyOffset(sourceZone, offsetReference);
        long localMillis = timeOfDayMillis(localTime);
        long utcMillis = Math.floorMod(
                localMillis - offset.getTotalSeconds() * 1000L,
                DAY_MILLIS);

        return compactTime(LocalTime.ofNanoOfDay(utcMillis * 1_000_000L));
    }

    /**
     * Return elapsed milliseconds, or NO_ELAPSED_MILLIS when unresolved.
     *
     * A dated start is compared directly.
     * A time-only start may refer to the same UTC day or previous UTC day and
     * is accepted only when the resulting elapsed time is strictly < 12 hours.
     */
    public static long elapsedMillisOrMissing(long compactUtc, long passageEpochMillis) {
        if (hasDate(compactUtc)) {
            long elapsed = passageEpochMillis
                    - dateTime(compactUtc).toInstant(ZoneOffset.UTC).toEpochMilli();

            return elapsed >= 0L ? elapsed : NO_ELAPSED_MILLIS;
        }

        long passageMillisOfDay = Math.floorMod(passageEpochMillis, DAY_MILLIS);
        long startMillisOfDay = timeOfDayMillis(time(compactUtc));
        long elapsed = passageMillisOfDay - startMillisOfDay;

        if (elapsed < 0L)
            elapsed += DAY_MILLIS;

        return elapsed < HALF_DAY_MILLIS ? elapsed : NO_ELAPSED_MILLIS;
    }

    public static boolean hasDate(long compactUtc) {
        if (compactUtc < 0L)
            throw new IllegalArgumentException("compactUtc must not be negative");

        return compactUtc >= DATE_THRESHOLD;
    }

    private static Instant localInstant(LocalDateTime localDateTime, ZoneId zone) {
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(localDateTime);

        if (offsets.size() != 1) {
            throw new IllegalArgumentException(
                    "local stage start is ambiguous or nonexistent in zone " + zone);
        }

        return localDateTime.toInstant(offsets.get(0));
    }

    private static ZoneOffset timeOnlyOffset(ZoneId zone, Instant offsetReference) {
        ZoneRules rules = zone.getRules();

        if (!rules.isFixedOffset()) {
            Instant lower = offsetReference.minusMillis(HALF_DAY_MILLIS);
            Instant upper = offsetReference.plusMillis(HALF_DAY_MILLIS);

            ZoneOffsetTransition previous =
                    rules.previousTransition(offsetReference.plusNanos(1L));
            ZoneOffsetTransition next =
                    rules.nextTransition(offsetReference.minusNanos(1L));

            if (previous != null && !previous.getInstant().isBefore(lower)) {
                throw new IllegalArgumentException(
                        "time-only stage start is ambiguous near a zone transition");
            }

            if (next != null && !next.getInstant().isAfter(upper)) {
                throw new IllegalArgumentException(
                        "time-only stage start is ambiguous near a zone transition");
            }
        }

        return rules.getOffset(offsetReference);
    }

    private static long compactDateTime(LocalDateTime value) {
        return value.getYear() * 1_000_000_000_000L
                + value.getMonthValue() * 10_000_000_000L
                + value.getDayOfMonth() * 100_000_000L
                + compactTime(value.toLocalTime());
    }

    private static long compactTime(LocalTime value) {
        return value.getHour() * 1_000_000L
                + value.getMinute() * 10_000L
                + value.getSecond() * 100L
                + value.getNano() / CENTISECOND_NANOS;
    }

    private static LocalDateTime dateTime(long compactUtc) {
        if (!hasDate(compactUtc))
            throw new IllegalArgumentException("compactUtc has no date");

        long remainder = compactUtc;
        int year = (int) (remainder / 1_000_000_000_000L);
        remainder %= 1_000_000_000_000L;
        int month = (int) (remainder / 10_000_000_000L);
        remainder %= 10_000_000_000L;
        int day = (int) (remainder / 100_000_000L);
        remainder %= 100_000_000L;

        try {
            return LocalDateTime.of(
                    LocalDate.of(year, month, day),
                    time(remainder));
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException(
                    "invalid compact UTC date/time " + compactUtc,
                    ex);
        }
    }

    private static LocalTime time(long compactUtc) {
        if (compactUtc < 0L)
            throw new IllegalArgumentException("compactUtc must not be negative");
        if (compactUtc >= DATE_THRESHOLD)
            throw new IllegalArgumentException("compactUtc time part is too large");

        int hour = (int) (compactUtc / 1_000_000L);
        long remainder = compactUtc % 1_000_000L;
        int minute = (int) (remainder / 10_000L);
        remainder %= 10_000L;
        int second = (int) (remainder / 100L);
        int centisecond = (int) (remainder % 100L);

        try {
            return LocalTime.of(
                    hour,
                    minute,
                    second,
                    centisecond * (int) CENTISECOND_NANOS);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException(
                    "invalid compact UTC time " + compactUtc,
                    ex);
        }
    }

    private static long timeOfDayMillis(LocalTime value) {
        return value.toNanoOfDay() / 1_000_000L;
    }
}
