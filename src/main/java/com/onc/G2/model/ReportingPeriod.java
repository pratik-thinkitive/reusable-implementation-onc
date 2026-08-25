package com.onc.G2.model;

import java.time.LocalDate;

/**
 * The date window a measure is reported over.
 *
 * <p>Seven places used to repeat the same "current calendar year" defaulting, so they could
 * drift apart. They all go through this now.
 */
public record ReportingPeriod(LocalDate start, LocalDate end) {

    /** The fallback the endpoints have always used when a caller omits the dates. */
    public static ReportingPeriod currentCalendarYear() {
        LocalDate today = LocalDate.now();
        return new ReportingPeriod(
                today.withDayOfYear(1),
                today.withMonth(12).withDayOfMonth(31));
    }

    /**
     * Builds a period, defaulting either end to the current calendar year when it is missing.
     * Each side defaults independently, so supplying only a start keeps the default end.
     */
    public static ReportingPeriod of(LocalDate start, LocalDate end) {
        ReportingPeriod defaults = currentCalendarYear();
        return new ReportingPeriod(
                start != null ? start : defaults.start(),
                end != null ? end : defaults.end());
    }

    /**
     * Builds a period from ISO {@code yyyy-MM-dd} text, treating null or empty as absent.
     *
     * @throws java.time.format.DateTimeParseException if a non-empty value is not a valid date
     */
    public static ReportingPeriod parse(String start, String end) {
        return of(parseOrNull(start), parseOrNull(end));
    }

    private static LocalDate parseOrNull(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return LocalDate.parse(value);
    }
}
