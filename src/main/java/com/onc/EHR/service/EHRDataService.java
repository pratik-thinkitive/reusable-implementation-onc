package com.onc.EHR.service;

import com.onc.EHR.dto.*;

import java.util.List;

/**
 * Reads from the EHR provider API.
 *
 * <p>Every method returns the payload itself and signals failure by throwing
 * {@link com.onc.common.exception.AppException}; nothing here builds an HTTP response. A read
 * that finds nothing yields an empty list, or throws {@code NOT_FOUND} where a single resource
 * was addressed - the 204s the previous signature produced could not survive the response
 * envelope, which always carries a body.
 */
public interface EHRDataService {

    MedicalDetailsData fetchPatientMedicalDetails(String fhirId);

    PersonalDetailsData fetchPatientPersonalDetails(String fhirId);

    List<InsuranceDetails> fetchPatientInsuranceDetails(String fhirId);

    AppointmentData fetchAppointments(String fhirId, String clinicId);

    DoctorDetailsData fetchDoctorDetails(int doctorId);

    List<FormData> fetchSoapDetails(String fhirId);

    Clinic fetchClinicDetails(int clinicId);

    List<Clinic> fetchAllClinicsByOrganisationId(int organisationId);

    List<DoctorDetailsData> fetchAllDoctorsByClinicId(String clinicId);

    /** Extracts the provider-local patient id from a composite FHIR id of the form {@code org-patient}. */
    String extractPatientId(String patientFhirId);
}
