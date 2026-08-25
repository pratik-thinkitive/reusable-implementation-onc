package com.onc.G2.service;

import com.onc.G2.dto.AccessDashboardResponse;
import com.onc.G2.dto.AccessRequestResponse;
import com.onc.G2.model.ReportingPeriod;

/** The admin side of G2: acting on requests, and reading measure performance. */
public interface PatientAccessAdminService {

    /** Approves a pending request and brings the counters up to date. */
    AccessRequestResponse grantAccess(Long requestId);

    /** Withdraws access and takes the patient back out of the numerator. */
    AccessRequestResponse revokeAccess(Long requestId);

    /** Narrowed to a single TIN when one is given. */
    AccessDashboardResponse getProviderDashboard(Integer organisationId,
                                                 String providerId,
                                                 String tinId,
                                                 ReportingPeriod period);

    /** Every provider billing under one TIN. */
    AccessDashboardResponse getGroupDashboard(String tinId, ReportingPeriod period);
}
