package com.onc.QRDA.controller;

import com.onc.EHR.dto.*;
import com.onc.QRDA.service.QRDACMSService;
import com.onc.api.support.ApiResponse;
import com.onc.api.support.BaseController;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

/**
 * QRDA Category I endpoints for CMS139.
 *
 * <p>{@link #getQrda} and {@link #generateQrdaZip} are the two endpoints in the application that
 * do not answer with the {@link ApiResponse} envelope: they return the generated XML and a ZIP
 * of it. Their <em>failures</em> still come back enveloped, from the global handler.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/ehr/cms/qrda")
public class QRDACMSController extends BaseController {

    private final QRDACMSService qrdacmsService;

    /** Binary: the generated QRDA document itself. */
    @GetMapping("/file")
    public ResponseEntity<byte[]> getQrda(@RequestParam String fhirId) {
        return qrdacmsService.getQrda(fhirId);
    }

    /** Binary: a ZIP of one document per requested patient. */
    @PostMapping("/zip")
    public ResponseEntity<?> generateQrdaZip(@RequestBody List<String> fhirIds) throws IOException {
        return qrdacmsService.generateQrdaZip(fhirIds);
    }

    @GetMapping("/medical-details")
    public ResponseEntity<ApiResponse<MedicalDetailsData>> fetchPatientMedicalDetails(
            @RequestParam String fhirId) {
        return data(qrdacmsService.fetchPatientMedicalDetails(fhirId));
    }

    @GetMapping("/personal-details")
    public ResponseEntity<ApiResponse<PersonalDetailsData>> fetchPatientPersonalDetails(
            @RequestParam String fhirId) {
        return data(qrdacmsService.fetchPatientPersonalDetails(fhirId));
    }

    @GetMapping("/soap-details")
    public ResponseEntity<ApiResponse<List<FormData>>> fetchSoapDetails(@RequestParam String fhirId) {
        return data(qrdacmsService.fetchSoapDetails(fhirId));
    }

    @GetMapping("/insurance-details")
    public ResponseEntity<ApiResponse<List<InsuranceDetails>>> fetchPatientInsuranceDetails(
            @RequestParam String fhirId) {
        return data(qrdacmsService.fetchPatientInsuranceDetails(fhirId));
    }

    @GetMapping("/appointment-details")
    public ResponseEntity<ApiResponse<AppointmentData>> fetchAppointments(
            @RequestParam String fhirId,
            @RequestParam(required = false) String clinicId) {
        return data(qrdacmsService.fetchAppointments(fhirId, clinicId));
    }

    @GetMapping("/doctor-details")
    public ResponseEntity<ApiResponse<DoctorDetailsData>> fetchDoctorDetails(@RequestParam int doctorId) {
        return data(qrdacmsService.fetchDoctorDetails(doctorId));
    }
}
