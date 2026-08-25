package com.onc.G2.service;

import com.onc.G2.dto.AccessDashboardResponse;
import com.onc.G2.dto.AccessRequestResponse;
import com.onc.G2.model.ReportingPeriod;

/**
 * The administrator's side of the G2 measure: acting on requests, and reading performance.
 *
 * <p>Coordinates the request and data services, which {@code PatientAccessAdminController}
 * used to do.
 */
public interface PatientAccessAdminService {

    /**
     * Approves a pending request and brings the measure counters up to date. The result carries
     * no HTTP meaning, so the controller picks the status code.
     */
    AccessRequestResponse grantAccess(Long requestId);

    /** Withdraws granted access and takes the patient back out of the numerator. */
    AccessRequestResponse revokeAccess(Long requestId);

    /** Performance for one provider, optionally narrowed to a single TIN. */
    AccessDashboardResponse getProviderDashboard(Integer organisationId,
                                                 String providerId,
                                                 String tinId,
                                                 ReportingPeriod period);

    /** Performance across every provider billing under one TIN. */
    AccessDashboardResponse getGroupDashboard(String tinId, ReportingPeriod period);
}
