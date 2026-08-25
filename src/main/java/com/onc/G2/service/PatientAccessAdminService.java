package com.onc.G2.service;

import com.onc.G2.dto.AccessDashboardResponse;
import com.onc.G2.dto.AccessRequestResponse;
import com.onc.G2.model.ReportingPeriod;

/**
 * The administrator's side of the G2 measure: acting on requests, and reading the resulting
 * measure performance.
 *
 * <p>Like {@link PatientAccessWorkflowService}, this sits above the request and data services and
 * coordinates them. That coordination used to live in {@code PatientAccessAdminController}.
 */
public interface PatientAccessAdminService {

    /**
     * Approves a pending request and brings the measure counters up to date.
     *
     * <p>The returned object reports whether the approval was allowed; it carries no HTTP
     * meaning, so the controller decides the status code.
     */
    AccessRequestResponse grantAccess(Long requestId);

    /**
     * Withdraws access that was previously granted and takes the patient back out of the
     * numerator.
     */
    AccessRequestResponse revokeAccess(Long requestId);

    /** Measure performance for one provider, optionally narrowed to a single TIN. */
    AccessDashboardResponse getProviderDashboard(Integer organisationId,
                                                 String providerId,
                                                 String tinId,
                                                 ReportingPeriod period);

    /** Measure performance across every provider billing under one TIN. */
    AccessDashboardResponse getGroupDashboard(String tinId, ReportingPeriod period);
}
