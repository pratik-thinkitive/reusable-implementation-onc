package com.onc.G2.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * A dashboard view: the patients counted in a reporting period, plus the measure totals.
 *
 * <p>Replaces the {@code Map<String, Object>} the controllers used to assemble by hand. A real
 * type means the shape is declared in one place and can be seen by callers and tests.
 *
 * <p><b>Why the two Jackson annotations matter.</b> The old code used a {@code LinkedHashMap},
 * which writes keys in the order they were inserted. A plain Java object instead writes them in
 * alphabetical order, so switching to this class would silently reshuffle the JSON. The explicit
 * {@code @JsonPropertyOrder} pins the original order. {@code @JsonInclude(NON_NULL)} keeps
 * {@code groupId} out of the per-provider response entirely, rather than emitting it as null -
 * again matching what the map produced.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "groupId",
        "patientsWithAccess",
        "reportingPeriodStart",
        "reportingPeriodEnd",
        "totalNumerator",
        "totalDenominator",
        "percentage"
})
public class AccessDashboardResponse {

    /** Only set on the group (TIN-wide) dashboard; omitted from the per-provider one. */
    private String groupId;

    private List<PatientAccessDataDto> patientsWithAccess;

    private LocalDate reportingPeriodStart;
    private LocalDate reportingPeriodEnd;

    /** Patients who were given access. */
    private int totalNumerator;

    /** Patients eligible to be given access. */
    private int totalDenominator;

    /** {@code totalNumerator / totalDenominator * 100}, or zero when the denominator is zero. */
    private double percentage;
}
