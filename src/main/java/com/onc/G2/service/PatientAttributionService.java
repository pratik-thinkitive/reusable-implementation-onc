package com.onc.G2.service;

import com.onc.G2.dto.PatientAttribution;

/** Reads a patient's identity and billing attribution from the upstream EHR. */
public interface PatientAttributionService {

    /**
     * Never throws - an unreachable EHR yields an attribution with null fields.
     *
     * @param patientFhirId composite id of the form {@code organisation-patient}
     */
    PatientAttribution lookup(String patientFhirId);
}
