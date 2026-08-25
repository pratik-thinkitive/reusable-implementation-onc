package com.onc.G2.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Dashboard totals plus the patients behind them.
 * Key order and the missing groupId are part of the payload consumers see, so both are pinned.
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

    /** Group dashboard only; left out of the per-provider response entirely. */
    private String groupId;

    private List<PatientAccessDataDto> patientsWithAccess;

    private LocalDate reportingPeriodStart;
    private LocalDate reportingPeriodEnd;

    /** Patients given access. */
    private int totalNumerator;

    /** Patients eligible for access. */
    private int totalDenominator;

    private double percentage;
}
