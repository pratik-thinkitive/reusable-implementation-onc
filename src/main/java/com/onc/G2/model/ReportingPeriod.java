package com.onc.G2.model;

import java.time.LocalDate;

/**
 * The date window a measure is reported over, as a single value instead of two loose
 * {@code LocalDate} parameters that have to be passed around together.
 *
 * <p>Before this type existed, seven different places each repeated the same two lines to work
 * out "the current calendar year", which meant seven places that could drift apart. Everything
 * now goes through {@link #of(LocalDate, LocalDate)} or {@link #currentCalendarYear()}.
 *
 * <p>This is a {@code record}: Java generates the constructor, the {@code start()} and
 * {@code end()} accessors, {@code equals}, {@code hashCode} and {@code toString} for us. It is
 * immutable - once built, a period never changes.
 */
public record ReportingPeriod(LocalDate start, LocalDate end) {

    /**
     * The default window: 1 January to 31 December of the current year.
     *
     * <p>This is the fallback the endpoints have always used when a caller omits the dates.
     */
    public static ReportingPeriod currentCalendarYear() {
        LocalDate today = LocalDate.now();
        return new ReportingPeriod(
                today.withDayOfYear(1),
                today.withMonth(12).withDayOfMonth(31));
    }

    /**
     * Builds a period from caller-supplied dates, falling back to the current calendar year for
     * whichever end is missing.
     *
     * <p>Each side falls back independently, which is exactly what the controllers did before:
     * supplying only a start date keeps the default end date.
     *
     * @param start first day of the period, or {@code null} to default it
     * @param end   last day of the period, or {@code null} to default it
     */
    public static ReportingPeriod of(LocalDate start, LocalDate end) {
        ReportingPeriod defaults = currentCalendarYear();
        return new ReportingPeriod(
                start != null ? start : defaults.start(),
                end != null ? end : defaults.end());
    }

    /**
     * Builds a period from ISO date text ({@code yyyy-MM-dd}), treating null or empty as absent.
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
