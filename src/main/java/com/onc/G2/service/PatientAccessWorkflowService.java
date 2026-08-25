package com.onc.G2.service;

import com.onc.G2.dto.AccessRequestResult;

/** The patient's side of G2: asking for access, and viewing data once granted. */
public interface PatientAccessWorkflowService {

    /** Also records the view, which is what counts the patient towards the numerator. */
    boolean checkAccessAndRecordView(String patientFhirId);

    /** Reporting dates are optional ISO text; absent means the current calendar year. */
    AccessRequestResult requestAccess(String patientFhirId,
                                      String requestType,
                                      String encounterId,
                                      String providerId,
                                      String tinId,
                                      String reportingPeriodStart,
                                      String reportingPeriodEnd);
}
