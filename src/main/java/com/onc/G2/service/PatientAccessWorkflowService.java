package com.onc.G2.service;

import com.onc.G2.dto.AccessRequestResult;
import com.onc.G2.enums.RequestType;
import com.onc.G2.model.ReportingPeriod;

/** The patient's side of G2: asking for access, and viewing data once granted. */
public interface PatientAccessWorkflowService {

    /** Also records the view, which is what counts the patient towards the numerator. */
    boolean checkAccessAndRecordView(String patientFhirId);

    /**
     * Files an access request, or reports the existing one that blocks it.
     *
     * <p>The reporting period is resolved by the caller, so an omitted date has already been
     * defaulted to the current calendar year by the time it gets here.
     */
    AccessRequestResult requestAccess(String patientFhirId,
                                      RequestType requestType,
                                      String encounterId,
                                      String providerId,
                                      String tinId,
                                      ReportingPeriod period);
}
