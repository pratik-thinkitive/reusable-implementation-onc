package com.onc.G2.service;

import com.onc.G2.dto.PatientAttribution;

/** Looks up who a patient is and who they are attributed to, by reading the upstream EHR. */
public interface PatientAttributionService {

    /**
     * Resolves a patient's name, organisation, provider and TIN. Never throws and never returns
     * null - an unreachable EHR yields null fields instead.
     *
     * @param patientFhirId composite FHIR id of the form {@code organisation-patient}
     */
    PatientAttribution lookup(String patientFhirId);
}
