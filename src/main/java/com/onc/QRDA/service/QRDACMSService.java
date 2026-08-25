package com.onc.QRDA.service;

import com.onc.EHR.dto.*;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.List;

public interface QRDACMSService {

    ResponseEntity<byte[]> getQrda(String fhirId);

    ResponseEntity<MedicalDetailsData> fetchPatientMedicalDetails(String fhirId);

    ResponseEntity<PersonalDetailsData> fetchPatientPersonalDetails(String fhirId);

    ResponseEntity<List<InsuranceDetails>> fetchPatientInsuranceDetails(String fhirId);

    ResponseEntity<AppointmentData> fetchAppointments(String fhirId, String clinicId);

    ResponseEntity<DoctorDetailsData> fetchDoctorDetails(int doctorId);

    ResponseEntity<List<FormData>> fetchSoapDetails(String fhirId);

    String getQrdaXml(String fhirId);

    ResponseEntity<?> generateQrdaZip(List<String> fhirIds) throws IOException;
}
