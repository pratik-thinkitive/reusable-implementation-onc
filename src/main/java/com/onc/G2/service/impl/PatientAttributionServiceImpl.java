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
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Resolves patient attribution by walking three EHR endpoints in turn.
 *
 * <p>The walk is: <b>personal details</b> gives the patient's name, organisation and the id of
 * the doctor who created the record; <b>doctor details</b> gives that doctor's clinics; and
 * <b>clinic details</b> gives a clinic's TIN. The first clinic that has a TIN wins.
 *
 * <p>This code was previously four private methods inside {@code G2Controller}. It is moved here
 * unchanged so that the controller is left with nothing but request handling.
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
            ResponseEntity<PersonalDetailsData> personalDetailsResponse =
                    ehrDataService.fetchPatientPersonalDetails(patientFhirId);

            if (personalDetailsResponse == null
                    || !personalDetailsResponse.getStatusCode().is2xxSuccessful()
                    || personalDetailsResponse.getBody() == null) {
                log.warn("Failed to fetch personal details from EHRDataService for fhirId: {}", patientFhirId);
                return new PatientAttribution();
            }

            PersonalDetailsData personalDetails = personalDetailsResponse.getBody();

            PatientAttribution attribution = new PatientAttribution();
            attribution.setOrganisationId(personalDetails.getOrganisationId());
            attribution.setProviderId(String.valueOf(personalDetails.getCreatedBy()));
            attribution.setTinId(findTinForDoctor(personalDetails.getCreatedBy()));

            applyPatientName(personalDetails, attribution, patientFhirId);

            log.info("Successfully extracted patient details - FirstName: {}, LastName: {}, "
                            + "OrganisationId: {}, ProviderId: {}, TinId: {}",
                    attribution.getFirstName(), attribution.getLastName(),
                    attribution.getOrganisationId(), attribution.getProviderId(),
                    attribution.getTinId());

            return attribution;

        } catch (Exception e) {
            log.error("Error extracting patient details from EHRDataService for fhirId: {}", patientFhirId, e);
            return new PatientAttribution();
        }
    }

    /** Copies the patient's first and last name across, if the EHR returned any. */
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
        log.info("Extracted Patient Name: {} {}", info.getFirstName(), info.getLastName());
    }

    /** Returns the TIN of the doctor's first clinic that has one, or {@code null} if none do. */
    private String findTinForDoctor(int doctorId) {
        List<Integer> clinicIds = fetchClinicIdsByDoctorId(doctorId);

        for (Integer clinicId : clinicIds) {
            String tinId = extractTinIdFromClinicDetails(clinicId);
            if (tinId != null && !tinId.isEmpty()) {
                log.info("Found valid TIN ID: {} for clinic ID: {}", tinId, clinicId);
                return tinId;
            }
        }

        log.warn("No valid TIN ID found for doctor {}", doctorId);
        return null;
    }

    private List<Integer> fetchClinicIdsByDoctorId(int doctorId) {
        ResponseEntity<DoctorDetailsData> doctorResponse = ehrDataService.fetchDoctorDetails(doctorId);

        if (doctorResponse != null && doctorResponse.getStatusCode().is2xxSuccessful()
                && doctorResponse.getBody() != null) {
            List<Clinic> clinics = doctorResponse.getBody().getClinics();
            if (clinics != null) {
                return clinics.stream().map(Clinic::getClinic_id).collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    private String extractTinIdFromClinicDetails(int clinicId) {
        try {
            ResponseEntity<Clinic> clinicResponse = ehrDataService.fetchClinicDetails(clinicId);

            if (clinicResponse != null && clinicResponse.getStatusCode().is2xxSuccessful()
                    && clinicResponse.getBody() != null) {

                Clinic clinicDetails = clinicResponse.getBody();
                String tin = clinicDetails.getTax_identification_number();

                if (tin != null && !tin.isEmpty()) {
                    log.info("Successfully extracted TIN ID: {} for clinic: {}", tin, clinicId);
                    return tin;
                }
            }

            log.warn("Could not extract TIN ID for clinic: {}", clinicId);
            return null;

        } catch (Exception e) {
            log.error("Error extracting TIN ID for clinic: {}", clinicId, e);
            return null;
        }
    }
}
