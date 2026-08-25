package com.onc.G2.service;

import com.onc.G2.dto.PatientAttribution;

/**
 * Looks up who a patient is and who they are attributed to, by reading the upstream EHR.
 */
public interface PatientAttributionService {

    /**
     * Resolves a patient's name, organisation, provider and TIN from the EHR.
     *
     * <p>Never throws and never returns {@code null}: if the EHR cannot be reached or returns
     * nothing useful, the returned object simply has null fields.
     *
     * @param patientFhirId composite FHIR id of the form {@code organisation-patient}
     */
    PatientAttribution lookup(String patientFhirId);
}
