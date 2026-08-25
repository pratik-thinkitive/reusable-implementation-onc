package com.onc.G2.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * A dashboard view: the patients counted in a reporting period, plus the measure totals.
 *
 * <p>This replaced a LinkedHashMap, which wrote keys in insertion order while a plain object
 * writes them alphabetically. The two annotations below keep the JSON identical.
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

    /** Only set on the group dashboard; omitted entirely from the per-provider one. */
    private String groupId;

    private List<PatientAccessDataDto> patientsWithAccess;

    private LocalDate reportingPeriodStart;
    private LocalDate reportingPeriodEnd;

    /** Patients who were given access. */
    private int totalNumerator;

    /** Patients eligible to be given access. */
    private int totalDenominator;

    private double percentage;
}
