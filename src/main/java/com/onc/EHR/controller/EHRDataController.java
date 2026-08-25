package com.onc.EHR.controller;

import com.onc.EHR.dto.*;
import com.onc.EHR.service.EHRDataService;
import com.onc.api.support.ApiResponse;
import com.onc.api.support.BaseController;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Vendor-neutral read surface over the EHR provider API.
 *
 * <p>Six of these endpoints duplicate the QRDA controller exactly; those remain available on
 * their original QRDA paths for compatibility, and are exposed here too so a module that needs
 * EHR data does not have to reach through a measure-specific controller to get it.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/ehr/data")
public class EHRDataController extends BaseController {

    private final EHRDataService ehrDataService;

    @GetMapping("/medical-details")
    public ResponseEntity<ApiResponse<MedicalDetailsData>> fetchPatientMedicalDetails(
            @RequestParam String fhirId) {
        return data(ehrDataService.fetchPatientMedicalDetails(fhirId));
    }

    @GetMapping("/personal-details")
    public ResponseEntity<ApiResponse<PersonalDetailsData>> fetchPatientPersonalDetails(
            @RequestParam String fhirId) {
        return data(ehrDataService.fetchPatientPersonalDetails(fhirId));
    }

    @GetMapping("/insurance-details")
    public ResponseEntity<ApiResponse<List<InsuranceDetails>>> fetchPatientInsuranceDetails(
            @RequestParam String fhirId) {
        return data(ehrDataService.fetchPatientInsuranceDetails(fhirId));
    }

    @GetMapping("/appointment-details")
    public ResponseEntity<ApiResponse<AppointmentData>> fetchAppointments(
            @RequestParam String fhirId,
            @RequestParam(required = false) String clinicId) {
        return data(ehrDataService.fetchAppointments(fhirId, clinicId));
    }

    @GetMapping("/doctor-details")
    public ResponseEntity<ApiResponse<DoctorDetailsData>> fetchDoctorDetails(@RequestParam int doctorId) {
        return data(ehrDataService.fetchDoctorDetails(doctorId));
    }

    @GetMapping("/soap-details")
    public ResponseEntity<ApiResponse<List<FormData>>> fetchSoapDetails(@RequestParam String fhirId) {
        return data(ehrDataService.fetchSoapDetails(fhirId));
    }

    @GetMapping("/clinic-details")
    public ResponseEntity<ApiResponse<Clinic>> fetchClinicDetails(@RequestParam int clinicId) {
        return data(ehrDataService.fetchClinicDetails(clinicId));
    }

    @GetMapping("/clinics")
    public ResponseEntity<ApiResponse<List<Clinic>>> fetchAllClinicsByOrganisationId(
            @RequestParam int organisationId) {
        return data(ehrDataService.fetchAllClinicsByOrganisationId(organisationId));
    }

    @GetMapping("/providers")
    public ResponseEntity<ApiResponse<List<DoctorDetailsData>>> fetchAllDoctorsByClinicId(
            @RequestParam String clinicId) {
        return data(ehrDataService.fetchAllDoctorsByClinicId(clinicId));
    }
}
