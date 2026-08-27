package com.onc.C1.service;

import com.onc.EHR.dto.*;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.List;

/**
 * QRDA Category I generation for CMS139, plus pass-throughs to {@link com.onc.EHR.service.EHRDataService}
 * that keep the original QRDA endpoint paths working.
 *
 * <p>{@link #getQrda} and {@link #generateQrdaZip} still return a {@code ResponseEntity}: they
 * answer with XML and a ZIP, the two responses that cannot be carried inside the JSON envelope.
 */
public interface QRDACMSService {

    ResponseEntity<byte[]> getQrda(String fhirId);

    MedicalDetailsData fetchPatientMedicalDetails(String fhirId);

    PersonalDetailsData fetchPatientPersonalDetails(String fhirId);

    List<InsuranceDetails> fetchPatientInsuranceDetails(String fhirId);

    AppointmentData fetchAppointments(String fhirId, String clinicId);

    DoctorDetailsData fetchDoctorDetails(int doctorId);

    List<FormData> fetchSoapDetails(String fhirId);

    String getQrdaXml(String fhirId);

    ResponseEntity<?> generateQrdaZip(List<String> fhirIds) throws IOException;
}
