package com.onc.G2.service;

import com.onc.G2.dto.AccessRequestResult;

/**
 * The patient's side of the G2 journey: asking for access, and viewing data once granted.
 *
 * <p>Coordinates {@link PatientAccessRequestService} and {@link PatientAccessDataService}, which
 * is what {@code G2Controller} used to do.
 */
public interface PatientAccessWorkflowService {

    /**
     * Checks whether a patient has access and, if so, records the view - which is what puts them
     * in the numerator. The side effect is intentional: the measure counts access being used.
     *
     * @return true if the patient may see their health information
     */
    boolean checkAccessAndRecordView(String patientFhirId);

    /**
     * Records a patient's request for access and counts the encounter that prompted it.
     * Reporting dates are optional ISO text; absent means the current calendar year.
     */
    AccessRequestResult requestAccess(String patientFhirId,
                                      String requestType,
                                      String encounterId,
                                      String providerId,
                                      String tinId,
                                      String reportingPeriodStart,
                                      String reportingPeriodEnd);
}
