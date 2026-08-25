package com.onc.EHR.controller;

import com.onc.EHR.dto.*;
import com.onc.EHR.service.EhrDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Vendor-neutral read surface over the EHR provider API.
 *
 * <p>Replaces the vendor-named controller the G2 module carried. Six of its endpoints
 * duplicated the QRDA controller exactly; those remain available on their original QRDA
 * paths for compatibility, and are exposed here too so a module that needs EHR data does
 * not have to reach through a measure-specific controller to get it.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/ehr/data")
public class EhrDataController {

    private final EhrDataService ehrDataService;

    @GetMapping("/medical-details")
    public ResponseEntity<MedicalDetailsData> fetchPatientMedicalDetails(@RequestParam String fhirId) {
        return ehrDataService.fetchPatientMedicalDetails(fhirId);
    }

    @GetMapping("/personal-details")
    public ResponseEntity<PersonalDetailsData> fetchPatientPersonalDetails(@RequestParam String fhirId) {
        return ehrDataService.fetchPatientPersonalDetails(fhirId);
    }

    @GetMapping("/insurance-details")
    public ResponseEntity<List<InsuranceDetails>> fetchPatientInsuranceDetails(@RequestParam String fhirId) {
        return ehrDataService.fetchPatientInsuranceDetails(fhirId);
    }

    @GetMapping("/appointment-details")
    public ResponseEntity<AppointmentData> fetchAppointments(@RequestParam String fhirId,
                                                             @RequestParam(required = false) String clinicId) {
        return ehrDataService.fetchAppointments(fhirId, clinicId);
    }

    @GetMapping("/doctor-details")
    public ResponseEntity<DoctorDetailsData> fetchDoctorDetails(@RequestParam int doctorId) {
        return ehrDataService.fetchDoctorDetails(doctorId);
    }

    @GetMapping("/soap-details")
    public ResponseEntity<List<FormData>> fetchSoapDetails(@RequestParam String fhirId) {
        return ehrDataService.fetchSoapDetails(fhirId);
    }
}
