package com.onc.G2.model;

import java.time.LocalDate;

/** The date window a measure is reported over. */
public record ReportingPeriod(LocalDate start, LocalDate end) {

    /** What the endpoints fall back to when a caller omits the dates. */
    public static ReportingPeriod currentCalendarYear() {
        LocalDate today = LocalDate.now();
        return new ReportingPeriod(
                today.withDayOfYear(1),
                today.withMonth(12).withDayOfMonth(31));
    }

    /** Each end defaults on its own, so passing only a start keeps the default end. */
    public static ReportingPeriod of(LocalDate start, LocalDate end) {
        ReportingPeriod defaults = currentCalendarYear();
        return new ReportingPeriod(
                start != null ? start : defaults.start(),
                end != null ? end : defaults.end());
    }

    /** Expects ISO {@code yyyy-MM-dd}; null or empty counts as not supplied. */
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
