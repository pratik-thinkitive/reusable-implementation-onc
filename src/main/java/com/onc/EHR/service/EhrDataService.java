package com.onc.EHR.service;

import com.onc.EHR.dto.*;
import org.springframework.http.ResponseEntity;

import java.util.List;

/**
 * Reads patient, provider and organisation data from the upstream EHR provider API.
 *
 * <p>Single definition shared by every certification module. QRDA and G2 previously carried
 * separate copies of the patient reads that differed only in method naming, so a change to
 * one silently left the other behind.
 *
 * <p>Response semantics are preserved from the original implementations: a non-2xx upstream
 * status is passed through with a null body, an empty collection yields {@code noContent},
 * and any failure becomes a 500.
 */
public interface EhrDataService {

    ResponseEntity<MedicalDetailsData> fetchPatientMedicalDetails(String fhirId);

    ResponseEntity<PersonalDetailsData> fetchPatientPersonalDetails(String fhirId);

    ResponseEntity<List<InsuranceDetails>> fetchPatientInsuranceDetails(String fhirId);

    ResponseEntity<AppointmentData> fetchAppointments(String fhirId, String clinicId);

    ResponseEntity<DoctorDetailsData> fetchDoctorDetails(int doctorId);

    ResponseEntity<List<FormData>> fetchSoapDetails(String fhirId);

    /** Extracts the provider-local patient id from a composite FHIR id of the form {@code org-patient}. */
    String extractPatientId(String patientFhirId);
}
