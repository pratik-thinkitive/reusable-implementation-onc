package com.onc.qrdaC1.QRDA.controller;

import com.onc.qrdaC1.QRDA.dto.*;
import com.onc.qrdaC1.QRDA.service.QRDACMSService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/ehr/cms/qrda")
public class QRDACMSController {

    public final QRDACMSService qrdacmsService;

    @GetMapping("/file")
    public ResponseEntity<byte[]> getQrda(@RequestParam String fhirId) {
        return qrdacmsService.getQrda(fhirId);
    }

    @GetMapping("/medical-details")
    public ResponseEntity<MedicalDetailsData> fetchPatientMedicalDetails(@RequestParam String fhirId) {
        return qrdacmsService.fetchPatientMedicalDetails(fhirId);
    }

    @GetMapping("/personal-details")
    public ResponseEntity<PersonalDetailsData> fetchPatientPersonalDetails(@RequestParam String fhirId) {
        return qrdacmsService.fetchPatientPersonalDetails(fhirId);
    }

    @GetMapping("/soap-details")
    public ResponseEntity<List<FormData>> fetchSoapDetails(String fhirId) {
        return qrdacmsService.fetchSoapDetails(fhirId);
    }

    @GetMapping("/insurance-details")
    public ResponseEntity<List<InsuranceDetails>> fetchPatientInsuranceDetails(@RequestParam String fhirId) {
        return qrdacmsService.fetchPatientInsuranceDetails(fhirId);
    }

    @GetMapping("/appointment-details")
    public ResponseEntity<AppointmentData> fetchAppointments(@RequestParam String fhirId, String clinicId) {
        return qrdacmsService.fetchAppointments(fhirId, clinicId);
    }

    @GetMapping("/doctor-details")
    public ResponseEntity<DoctorDetailsData> fetchDoctorDetails(@RequestParam int doctorId) {
        return qrdacmsService.fetchDoctorDetails(doctorId);
    }

    @PostMapping("/zip")
    public ResponseEntity<?> generateQrdaZip(@RequestBody List<String> fhirIds) throws IOException {
        return qrdacmsService.generateQrdaZip(fhirIds);
    }
}
