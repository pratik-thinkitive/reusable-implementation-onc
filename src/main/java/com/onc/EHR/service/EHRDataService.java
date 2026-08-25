package com.onc.EHR.service;

import com.onc.EHR.dto.*;
import org.springframework.http.ResponseEntity;

import java.util.List;


public interface EHRDataService {

    ResponseEntity<MedicalDetailsData> fetchPatientMedicalDetails(String fhirId);

    ResponseEntity<PersonalDetailsData> fetchPatientPersonalDetails(String fhirId);

    ResponseEntity<List<InsuranceDetails>> fetchPatientInsuranceDetails(String fhirId);

    ResponseEntity<AppointmentData> fetchAppointments(String fhirId, String clinicId);

    ResponseEntity<DoctorDetailsData> fetchDoctorDetails(int doctorId);

    ResponseEntity<List<FormData>> fetchSoapDetails(String fhirId);

    ResponseEntity<Clinic> fetchClinicDetails(int clinicId);

    ResponseEntity<List<Clinic>> fetchAllClinicsByOrganisationId(int organisationId);

    ResponseEntity<List<DoctorDetailsData>> fetchAllDoctorsByClinicId(String clinicId);

    /** Extracts the provider-local patient id from a composite FHIR id of the form {@code org-patient}. */
    String extractPatientId(String patientFhirId);
}
