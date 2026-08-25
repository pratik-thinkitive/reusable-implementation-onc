package com.onc.G2.service;

import com.onc.G2.dto.AccessRequestResult;

/**
 * The patient's side of the G2 journey: asking for access, and viewing data once granted.
 *
 * <p>Sits above {@link PatientAccessRequestService} (which owns the request's lifecycle) and
 * {@link PatientAccessDataService} (which owns the measure counters), and coordinates the two.
 * That coordination used to live in {@code G2Controller}.
 */
public interface PatientAccessWorkflowService {

    /**
     * Checks whether a patient currently has access and, if they do, records that they viewed
     * their information - which is what puts them in the measure numerator.
     *
     * <p>The side effect is intentional and pre-existing: under the measure, the patient
     * <em>having and using</em> access is the thing being counted.
     *
     * @return {@code true} if the patient may see their health information
     */
    boolean checkAccessAndRecordView(String patientFhirId);

    /**
     * Records a patient's request for access and counts the encounter that prompted it.
     *
     * <p>Reporting period dates are optional ISO {@code yyyy-MM-dd} text; when absent the
     * current calendar year is used.
     *
     * @throws IllegalArgumentException            if {@code requestType} is not a known type
     * @throws java.time.format.DateTimeParseException if a supplied date cannot be parsed
     */
    AccessRequestResult requestAccess(String patientFhirId,
                                      String requestType,
                                      String encounterId,
                                      String providerId,
                                      String tinId,
                                      String reportingPeriodStart,
                                      String reportingPeriodEnd);
}
