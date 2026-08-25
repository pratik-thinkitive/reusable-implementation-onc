package com.onc.G2.service.impl;

import com.onc.G2.dto.PatientAccessDataDto;
import com.onc.G2.entity.PatientAccessData;
import com.onc.G2.repository.PatientAccessDataRepository;
import com.onc.G2.service.PatientAccessDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PatientAccessDataServiceImpl implements PatientAccessDataService {

    private final PatientAccessDataRepository patientAccessDataRepository;

    @Override
    public PatientAccessDataDto initializePatientData(String patientFhirId, String patientId, String firstName, String lastName,
                                                      Integer organisationId, String providerId, String tinId,
                                                      LocalDateTime reportingPeriodStart, LocalDateTime reportingPeriodEnd) {

        log.info("Initializing patient data for: {} in reporting period: {} to {}", patientFhirId, reportingPeriodStart, reportingPeriodEnd);

        // Check if PatientAccessData already exists for this patient in this reporting period
        // If it exists, return the existing entry (i.e only ONE entry per patient per reporting period)
        Optional<PatientAccessData> existingMetrics = patientAccessDataRepository
                .findByPatientFhirIdAndReportingPeriodStartAndReportingPeriodEnd(patientFhirId, reportingPeriodStart, reportingPeriodEnd);

        if (existingMetrics.isPresent()) {
            log.info("Data already exists for patient: {} in reporting period. Returning existing entry.", patientFhirId);
            return convertToDto(existingMetrics.get());
        }

        // Create new PatientAccessData entry - this represents aggregated data across ALL encounters
        PatientAccessData metrics = new PatientAccessData();
        metrics.setPatientFhirId(patientFhirId);
        metrics.setPatientId(patientId);
        metrics.setFirstName(firstName);
        metrics.setLastName(lastName);
        metrics.setOrganisationId(organisationId);
        metrics.setProviderId(providerId);
        metrics.setTinId(tinId);
        metrics.setReportingPeriodStart(reportingPeriodStart);
        metrics.setReportingPeriodEnd(reportingPeriodEnd);
        metrics.setDenominatorCount(0);
        metrics.setNumeratorCount(0);
        metrics.setHasAccessGranted(false);
        metrics.setIsNumeratorRecorded(false);

        PatientAccessData savedMetrics = patientAccessDataRepository.save(metrics);
        log.info("Created new data entry with ID: {} for patient: {} (org: {}, provider: {}, tin: {})", savedMetrics.getId(), patientFhirId, organisationId, providerId, tinId);

        return convertToDto(savedMetrics);
    }

    @Override
    public void updateDenominator(String patientFhirId, LocalDateTime reportingPeriodStart,
                                  LocalDateTime reportingPeriodEnd, LocalDateTime encounterDate) {

        log.info("Updating denominator for patient: {} on encounter date: {}", patientFhirId, encounterDate);

        if (patientFhirId == null || reportingPeriodStart == null || reportingPeriodEnd == null || encounterDate == null) {
            log.warn("Null parameters provided for denominator update");
            return;
        }

        // Check if encounter date is within reporting period
        if (encounterDate.isBefore(reportingPeriodStart) || encounterDate.isAfter(reportingPeriodEnd)) {
            log.info("Encounter date: {} is outside reporting period: {} to {}",
                    encounterDate, reportingPeriodStart, reportingPeriodEnd);
            return;
        }

        PatientAccessData metrics = getOrCreateMetrics(patientFhirId, reportingPeriodStart, reportingPeriodEnd);

        // Check if this is the first encounter for this patient in the reporting period
        boolean isFirstEncounter = isFirstEncounterInReportingPeriod(patientFhirId, reportingPeriodStart, reportingPeriodEnd, encounterDate);

        if (isFirstEncounter) {
            // First encounter within measurement period - set denominator to 1
            metrics.setDenominatorCount(1);
            metrics.setFirstEncounterDate(encounterDate);
            log.info("Set denominator to 1 for patient: {} (first encounter) on date: {}", patientFhirId, encounterDate);
        } else {
            // Subsequent encounter within measurement period - ensure denominator is at least 1
            // If denominator is 0, set it to 1 (patient was seen even if not first encounter in period)
            if (metrics.getDenominatorCount() == 0) {
                metrics.setDenominatorCount(1);
                log.info("Set denominator to 1 for patient: {} (subsequent encounter, was 0) on date: {}", patientFhirId, encounterDate);
            } else {
                log.info("Retained denominator count as 1 for patient: {} (subsequent encounter) on date: {}", patientFhirId, encounterDate);
            }
        }

        patientAccessDataRepository.save(metrics);
    }

    @Override
    public void updateNumerator(String patientFhirId, LocalDateTime reportingPeriodStart,
                                LocalDateTime reportingPeriodEnd, boolean hasAccess, LocalDateTime accessDate) {

        log.info("Updating numerator for patient: {} with access: {} on date: {}",
                patientFhirId, hasAccess, accessDate);

        if (patientFhirId == null || reportingPeriodStart == null || reportingPeriodEnd == null || accessDate == null) {
            log.warn("Null parameters provided for numerator update");
            return;
        }

        // Check if access date is within reporting period
        if (accessDate.isBefore(reportingPeriodStart) || accessDate.isAfter(reportingPeriodEnd)) {
            log.info("Access date: {} is outside reporting period: {} to {}",
                    accessDate, reportingPeriodStart, reportingPeriodEnd);
            return;
        }

        PatientAccessData metrics = getOrCreateMetrics(patientFhirId, reportingPeriodStart, reportingPeriodEnd);

        // Ensure patient is in denominator first
        if (metrics.getDenominatorCount() == 0) {
            log.info("Patient: {} not in denominator, cannot update numerator", patientFhirId);
            return;
        }

        if (hasAccess) {
            // Patient has access to personal details - set numerator to 1
            if (metrics.getNumeratorCount() == 0) {
                metrics.setNumeratorCount(1);
                metrics.setHasAccessGranted(true);
                metrics.setAccessGrantedDate(accessDate);
                metrics.setIsNumeratorRecorded(true);
                log.info("Set numerator to 1 for patient: {} (access granted) on date: {}", patientFhirId, accessDate);
            } else {
                log.info("Numerator already set to 1 for patient: {} (access already granted)", patientFhirId);
            }
        } else {
            // Patient does not have access or access was revoked - set numerator to 0
            if (metrics.getNumeratorCount() > 0) {
                metrics.setNumeratorCount(0);
                metrics.setHasAccessGranted(false);
                metrics.setAccessRevokedDate(accessDate);
                log.info("Set numerator to 0 for patient: {} (access revoked) on date: {}", patientFhirId, accessDate);
            } else {
                log.info("Numerator already set to 0 for patient: {} (no access granted)", patientFhirId);
            }
        }

        patientAccessDataRepository.save(metrics);
    }

    @Override
    public void decrementNumerator(String patientFhirId, LocalDateTime reportingPeriodStart,
                                   LocalDateTime reportingPeriodEnd) {

        log.info("Decrementing numerator for patient: {}", patientFhirId);

        PatientAccessData metrics = getOrCreateMetrics(patientFhirId, reportingPeriodStart, reportingPeriodEnd);

        // If patient had access and admin revoked it, set numerator to 0
        if (metrics.getNumeratorCount() > 0) {
            metrics.setNumeratorCount(0);
            metrics.setHasAccessGranted(false);
            metrics.setAccessRevokedDate(LocalDateTime.now());
            log.info("Set numerator to 0 for patient: {} (access revoked by admin)", patientFhirId);
        } else {
            log.info("Numerator already 0 for patient: {} (no access to revoke)", patientFhirId);
        }

        patientAccessDataRepository.save(metrics);
    }

    @Override
    public PatientAccessDataDto getTinData(String tinId, LocalDateTime reportingPeriodStart,
                                           LocalDateTime reportingPeriodEnd) {
        List<PatientAccessData> metrics = patientAccessDataRepository.findByTinIdWithinReportingPeriod(tinId, reportingPeriodStart, reportingPeriodEnd);

        Map<String, PatientAccessData> uniquePatients = metrics.stream()
                .collect(Collectors.toMap(PatientAccessData::getPatientFhirId,
                        pad -> pad,
                        (existing, replacement) -> existing ));

        int totalDenominator = uniquePatients.values().stream().mapToInt(PatientAccessData::getDenominatorCount).sum();
        int totalNumerator = uniquePatients.values().stream().mapToInt(PatientAccessData::getNumeratorCount).sum();

        PatientAccessDataDto dto = new PatientAccessDataDto();
        dto.setTinId(tinId);
        dto.setReportingPeriodStart(reportingPeriodStart);
        dto.setReportingPeriodEnd(reportingPeriodEnd);
        dto.setDenominatorCount(totalDenominator);
        dto.setNumeratorCount(totalNumerator);
        dto.setPercentage(calculatePercentage(totalNumerator, totalDenominator));

        return dto;
    }

    @Override
    public PatientAccessDataDto getTinProviderData(String tinId, String providerId, LocalDateTime reportingPeriodStart,
                                                   LocalDateTime reportingPeriodEnd) {
        List<PatientAccessData> metrics = patientAccessDataRepository.findAllByTinAndProviderWithinPeriod(tinId, providerId, reportingPeriodStart, reportingPeriodEnd);

        int totalDenominator = metrics.stream().mapToInt(PatientAccessData::getDenominatorCount).sum();
        int totalNumerator = metrics.stream().mapToInt(PatientAccessData::getNumeratorCount).sum();

        PatientAccessDataDto dto = new PatientAccessDataDto();
        dto.setTinId(tinId);
        dto.setProviderId(providerId);
        dto.setReportingPeriodStart(reportingPeriodStart);
        dto.setReportingPeriodEnd(reportingPeriodEnd);
        dto.setDenominatorCount(totalDenominator);
        dto.setNumeratorCount(totalNumerator);
        dto.setPercentage(calculatePercentage(totalNumerator, totalDenominator));

        return dto;
    }

    @Override
    public List<PatientAccessDataDto> getAllPatientData(LocalDateTime reportingPeriodStart, LocalDateTime reportingPeriodEnd) {
        List<PatientAccessData> metrics = patientAccessDataRepository.findAllPatientsWithinReportingPeriod(reportingPeriodStart, reportingPeriodEnd);
        return metrics.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<PatientAccessDataDto> getAccessGrantedPatientsFiltered(
            Integer organisationId, String providerId, String tinId,
            LocalDateTime reportingPeriodStart, LocalDateTime reportingPeriodEnd) {

        List<PatientAccessData> allPatients = patientAccessDataRepository.getAccessGrantedPatients(reportingPeriodStart, reportingPeriodEnd);

        // Filter based on parameters
        return allPatients.stream()
                .filter(p -> organisationId == null || organisationId.equals(p.getOrganisationId()))
                .filter(p -> providerId == null || providerId.equals(p.getProviderId()))
                .filter(p -> tinId == null || tinId.equals(p.getTinId()))
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<PatientAccessDataDto> getAccessGrantedPatientsForGroup(String tinId, LocalDateTime reportingPeriodStart,
                                                                       LocalDateTime reportingPeriodEnd) {

        List<PatientAccessData> allPatients = patientAccessDataRepository.getAccessGrantedPatientsByTinId(tinId, reportingPeriodStart, reportingPeriodEnd);

        return allPatients.stream()
                .filter(p -> tinId == null || tinId.equals(p.getTinId()))
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    public Double calculatePercentage(Integer numerator, Integer denominator) {
        if (denominator == null || denominator == 0) {
            return 0.0;
        }
        return (double) numerator / denominator * 100.0;
    }


    private PatientAccessData getOrCreateMetrics(String patientFhirId, LocalDateTime reportingPeriodStart,
                                                 LocalDateTime reportingPeriodEnd) {
        Optional<PatientAccessData> existingMetrics = patientAccessDataRepository
                .findByPatientFhirIdAndReportingPeriodStartAndReportingPeriodEnd(
                        patientFhirId, reportingPeriodStart, reportingPeriodEnd);

        if (existingMetrics.isPresent()) {
            return existingMetrics.get();
        } else {
            // Create new metrics if they don't exist
            PatientAccessData metrics = new PatientAccessData();
            metrics.setPatientFhirId(patientFhirId);
            metrics.setReportingPeriodStart(reportingPeriodStart);
            metrics.setReportingPeriodEnd(reportingPeriodEnd);
            metrics.setDenominatorCount(0);
            metrics.setNumeratorCount(0);
            metrics.setHasAccessGranted(false);
            metrics.setIsNumeratorRecorded(false);

            // Save the entity before returning to ensure it's persisted
            return patientAccessDataRepository.save(metrics);
        }
    }

    private PatientAccessDataDto convertToDto(PatientAccessData metrics) {
        PatientAccessDataDto dto = new PatientAccessDataDto();
        dto.setId(metrics.getId());
        dto.setPatientFhirId(metrics.getPatientFhirId());
        dto.setPatientId(metrics.getPatientId());
        dto.setFirstName(metrics.getFirstName());
        dto.setLastName(metrics.getLastName());
        dto.setOrganisationId(metrics.getOrganisationId());
        dto.setProviderId(metrics.getProviderId());
        dto.setTinId(metrics.getTinId());
        dto.setReportingPeriodStart(metrics.getReportingPeriodStart());
        dto.setReportingPeriodEnd(metrics.getReportingPeriodEnd());
        dto.setDenominatorCount(metrics.getDenominatorCount());
        dto.setNumeratorCount(metrics.getNumeratorCount());
        dto.setHasAccessGranted(metrics.getHasAccessGranted());
        dto.setAccessGrantedDate(metrics.getAccessGrantedDate());
        dto.setAccessRevokedDate(metrics.getAccessRevokedDate());
        dto.setIsNumeratorRecorded(metrics.getIsNumeratorRecorded());
        dto.setFirstEncounterDate(metrics.getFirstEncounterDate());
        dto.setCreatedAt(metrics.getCreatedAt());
        dto.setUpdatedAt(metrics.getUpdatedAt());
        dto.setPercentage(calculatePercentage(metrics.getNumeratorCount(), metrics.getDenominatorCount()));
        return dto;
    }

    /**
     * Check if this is the first encounter for the patient in the reporting period
     */
    private boolean isFirstEncounterInReportingPeriod(String patientFhirId, LocalDateTime reportingPeriodStart,
                                                      LocalDateTime reportingPeriodEnd, LocalDateTime encounterDate) {
        // Check if there are any existing metrics for this patient in this reporting period
        Optional<PatientAccessData> existingMetrics = patientAccessDataRepository
                .findByPatientFhirIdAndReportingPeriodStartAndReportingPeriodEnd(
                        patientFhirId, reportingPeriodStart, reportingPeriodEnd);

        if (existingMetrics.isEmpty()) {
            // No existing metrics - this is the first encounter
            return true;
        }

        // Check if there's already a first encounter date recorded
        PatientAccessData metrics = existingMetrics.get();
        if (metrics.getFirstEncounterDate() == null) {
            // No first encounter date recorded - this is the first encounter
            return true;
        }

        // Compare with the recorded first encounter date
        return encounterDate.isBefore(metrics.getFirstEncounterDate()) ||
                encounterDate.isEqual(metrics.getFirstEncounterDate());
    }
}
