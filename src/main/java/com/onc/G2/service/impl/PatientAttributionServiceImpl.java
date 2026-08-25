package com.onc.G2.service.impl;

import com.onc.EHR.dto.Clinic;
import com.onc.EHR.dto.DoctorDetailsData;
import com.onc.EHR.dto.PatientInformation;
import com.onc.EHR.dto.PersonalDetailsData;
import com.onc.EHR.service.EHRDataService;
import com.onc.G2.dto.PatientAttribution;
import com.onc.G2.service.PatientAttributionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Takes three EHR calls: personal details give the name, organisation and creating doctor; the
 * doctor gives their clinics; the first clinic with a TIN wins.
 *
 * <p>This is the one place a failed EHR read is deliberately swallowed: an access request must
 * still be fileable when the provider directory is down, so the caller gets an attribution with
 * null fields rather than an error.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatientAttributionServiceImpl implements PatientAttributionService {

    private final EHRDataService ehrDataService;

    @Override
    public PatientAttribution lookup(String patientFhirId) {
        log.info("Extracting patient details from EHRDataService for fhirId: {}", patientFhirId);

        try {
            PersonalDetailsData personalDetails = ehrDataService.fetchPatientPersonalDetails(patientFhirId);

            if (personalDetails == null) {
                log.warn("Failed to fetch personal details from EHRDataService for fhirId: {}", patientFhirId);
                return new PatientAttribution();
            }

            PatientAttribution attribution = new PatientAttribution();
            attribution.setOrganisationId(personalDetails.getOrganisationId());
            attribution.setProviderId(String.valueOf(personalDetails.getCreatedBy()));
            attribution.setTinId(findTinForDoctor(personalDetails.getCreatedBy()));

            applyPatientName(personalDetails, attribution, patientFhirId);

            // Patient names and TINs are PHI, so only the ids they were resolved for are logged.
            log.info("Resolved attribution for fhirId: {} - organisationId: {}, providerId: {}, TIN present: {}",
                    patientFhirId, attribution.getOrganisationId(), attribution.getProviderId(),
                    attribution.getTinId() != null);

            return attribution;

        } catch (Exception e) {
            log.error("Error extracting patient details from EHRDataService for fhirId: {}", patientFhirId, e);
            return new PatientAttribution();
        }
    }

    private void applyPatientName(PersonalDetailsData personalDetails,
                                  PatientAttribution attribution,
                                  String patientFhirId) {

        if (personalDetails.getResponse() == null
                || personalDetails.getResponse().getPatientInformation() == null
                || personalDetails.getResponse().getPatientInformation().isEmpty()) {
            log.warn("No PatientInformation found in response for fhirId: {}", patientFhirId);
            return;
        }

        PatientInformation info = personalDetails.getResponse()
                .getPatientInformation()
                .values()
                .stream()
                .findFirst()
                .orElse(null);

        if (info == null) {
            return;
        }

        attribution.setFirstName(info.getFirstName());
        attribution.setLastName(info.getLastName());
    }

    private String findTinForDoctor(int doctorId) {
        for (Integer clinicId : fetchClinicIdsByDoctorId(doctorId)) {
            String tinId = extractTinIdFromClinicDetails(clinicId);
            if (tinId != null && !tinId.isEmpty()) {
                log.info("Found a TIN for clinic ID: {}", clinicId);
                return tinId;
            }
        }

        log.warn("No valid TIN ID found for doctor {}", doctorId);
        return null;
    }

    private List<Integer> fetchClinicIdsByDoctorId(int doctorId) {
        try {
            DoctorDetailsData doctor = ehrDataService.fetchDoctorDetails(doctorId);
            if (doctor != null && doctor.getClinics() != null) {
                return doctor.getClinics().stream().map(Clinic::getClinic_id).collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("Could not read clinics for doctor {}", doctorId);
        }
        return Collections.emptyList();
    }

    private String extractTinIdFromClinicDetails(int clinicId) {
        try {
            Clinic clinicDetails = ehrDataService.fetchClinicDetails(clinicId);

            if (clinicDetails != null) {
                String tin = clinicDetails.getTax_identification_number();
                if (tin != null && !tin.isEmpty()) {
                    return tin;
                }
            }

            log.warn("Could not extract TIN ID for clinic: {}", clinicId);
            return null;

        } catch (Exception e) {
            log.warn("Error reading clinic {} while resolving a TIN", clinicId);
            return null;
        }
    }
}
