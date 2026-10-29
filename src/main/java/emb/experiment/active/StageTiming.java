package emb.experiment.active;

import java.time.Instant;
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
            long elapsed = passageEpochMillis - compactDateTimeEpochMillis(compactUtc);
            return elapsed >= 0L ? elapsed : NO_ELAPSED_MILLIS;
        }

        long passageMillisOfDay = Math.floorMod(passageEpochMillis, DAY_MILLIS);
        long startMillisOfDay = compactTimeMillis(compactUtc);
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

    /**
     * Parses one dated compact UTC value directly to epoch millis.
     *
     * This is the hot RegistrationList mutation path. It intentionally avoids
     * constructing LocalDate/LocalTime/LocalDateTime objects for every cached
     * elapsed-time recalculation.
     */
    private static long compactDateTimeEpochMillis(long compactUtc) {
        if (!hasDate(compactUtc))
            throw new IllegalArgumentException("compactUtc has no date");

        long remainder = compactUtc;
        int year = (int) (remainder / 1_000_000_000_000L);
        remainder %= 1_000_000_000_000L;
        int month = (int) (remainder / 10_000_000_000L);
        remainder %= 10_000_000_000L;
        int day = (int) (remainder / 100_000_000L);
        remainder %= 100_000_000L;

        validateDate(year, month, day);
        long epochDay = epochDay(year, month, day);
        return epochDay * DAY_MILLIS + compactTimeMillis(remainder);
    }

    /** Parses HHmmsscc to milliseconds since UTC midnight without allocation. */
    private static long compactTimeMillis(long compactTime) {
        if (compactTime < 0L || compactTime >= DATE_THRESHOLD)
            throw new IllegalArgumentException("invalid compact UTC time " + compactTime);

        int hour = (int) (compactTime / 1_000_000L);
        long remainder = compactTime % 1_000_000L;
        int minute = (int) (remainder / 10_000L);
        remainder %= 10_000L;
        int second = (int) (remainder / 100L);
        int centisecond = (int) (remainder % 100L);

        if (hour < 0 || hour > 23
                || minute < 0 || minute > 59
                || second < 0 || second > 59
                || centisecond < 0 || centisecond > 99) {
            throw new IllegalArgumentException("invalid compact UTC time " + compactTime);
        }

        return hour * 3_600_000L
                + minute * 60_000L
                + second * 1_000L
                + centisecond * 10L;
    }

    private static void validateDate(int year, int month, int day) {
        if (year < 0 || year > 999_999_999)
            throw new IllegalArgumentException("invalid compact UTC year " + year);
        if (month < 1 || month > 12)
            throw new IllegalArgumentException("invalid compact UTC month " + month);

        int length;
        switch (month) {
            case 2:
                length = isLeapYear(year) ? 29 : 28;
                break;
            case 4:
            case 6:
            case 9:
            case 11:
                length = 30;
                break;
            default:
                length = 31;
                break;
        }

        if (day < 1 || day > length)
            throw new IllegalArgumentException("invalid compact UTC day " + day);
    }

    /** Gregorian date to epoch-day, equivalent to LocalDate.toEpochDay(). */
    private static long epochDay(int year, int month, int day) {
        long y = year;
        long total = 365L * y;

        if (y >= 0L) {
            total += (y + 3L) / 4L
                    - (y + 99L) / 100L
                    + (y + 399L) / 400L;
        } else {
            total -= y / -4L - y / -100L + y / -400L;
        }

        total += (367L * month - 362L) / 12L;
        total += day - 1L;

        if (month > 2) {
            total--;
            if (!isLeapYear(year))
                total--;
        }

        return total - 719_528L;
    }

    private static boolean isLeapYear(int year) {
        return (year & 3) == 0 && (year % 100 != 0 || year % 400 == 0);
    }

    private static long timeOfDayMillis(LocalTime value) {
        return value.toNanoOfDay() / 1_000_000L;
    }}
