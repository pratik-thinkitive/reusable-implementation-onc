package com.onc.C2.measure;

import com.onc.C2.dto.PatientMeasureData;
import com.onc.EHR.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Measure evaluator for C2 measures using QRDA DTOs
 * Evaluates CMS139 - Falls: Screening for Future Fall Risk
 * <p>
 * CMS139 Logic:
 * - IPP: Age ≥ 65 AND qualifying encounter (office visit/preventive visit/annual wellness visit) with correct codes AND encounter within measurement period
 * - DENOM: Same as IPP (unless exclusions apply)
 * - NUM: DENOM patients who had fall risk screening (LOINC 73830-2 or 73832-0) during/linked to qualifying encounter
 * - DENEX: Patients with exclusions (ED-only, inpatient-only, hospice, palliative care)
 */
@Slf4j
public class MeasureEvaluator {

    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MIN_AGE_FOR_MEASURE = 65; // CMS139 requires patients 65+ years old



    // Fall risk screening LOINC codes
    private static final Set<String> FALL_RISK_SCREENING_LOINC_CODES = Set.of(
        "73830-2", "73832-0", "LC-73830-2", "LC-73832-0" // Fall risk screening codes
    );

    // --- Qualifying CPT/HCPCS (as strings) -------------------------
    private static final Set<String> QUALIFYING_CPT = Set.of(
            "99201", "99202", "99203", "99204", "99205",
            "99211", "99212", "99213", "99214", "99215",
            "99381", "99382", "99383", "99384", "99385", "99386", "99387",
            "99391", "99392", "99393", "99394", "99395", "99396", "99397",
            "G0438", "G0439"
    );

    // --- Representative SNOMED encounter procedure codes (common subset) ---
    // Expand this list from the authoritative value set (VSAC) as needed.
    private static final Set<String> QUALIFYING_SNOMED = Set.of(
            "185349003", "440655000", "270427003", "185345009",
            "185463005", "3457005", "371883000", "172009006"
    );

    private static final Set<String> QUALIFYING_ENCOUNTER_CODES =
            Stream.concat(QUALIFYING_CPT.stream(), QUALIFYING_SNOMED.stream())
                    .collect(Collectors.toSet());

    // --- ED / Hospice / Palliative exclusion code examples (expand from VSAC) ---
    private static final Set<String> ED_EXCLUSION_CODES = Set.of(
            "50849002", "4525004", "308335008"
    );

    // Hospice care SNOMED CT codes (common codes for hospice care)
    // 183919006 = Hospice care (regime/therapy)
    // 305336008 = Hospice care (procedure)
    // Additional common hospice-related codes
    private static final Set<String> HOSPICE_CODES = Set.of(
            "183919006",  // Hospice care (regime/therapy)
            "305336008",  // Hospice care (procedure)
            "385763009",  // Palliative care (often used with hospice)
            "456661000124102",
            "170935008"// Palliative care procedure
    );

    private static final Set<String> PALLIATIVE_CODES = Set.of(
            "385763009", "456661000124102"
    );


    /**
     * Evaluate C2 measure for a patient (CMS139 - Falls: Screening for Future Fall Risk)
     * 
     * @param patientMeasureData Patient data with extracted encounters, assessments, interventions
     */
    public static void evaluateC2Measure(PatientMeasureData patientMeasureData, String measurementPeriodStart, String measurementPeriodEnd) {
        log.info("=== Evaluating CMS139 measure for patient {} with measurement period: {} to {} ===", 
                patientMeasureData.getPatientId(), measurementPeriodStart, measurementPeriodEnd);
        
        LocalDate periodStart = parseDate(measurementPeriodStart);
        LocalDate periodEnd = parseDate(measurementPeriodEnd);
        
        // Step 1: Check Initial Population (IPP)
        // IPP = Age ≥ 65 AND qualifying encounter with correct codes AND encounter within measurement period
        boolean inInitialPopulation = isInInitialPopulation(patientMeasureData, periodStart, periodEnd);
        patientMeasureData.setInInitialPopulation(inInitialPopulation);
        
        if (!inInitialPopulation) {
            // Patient not in initial population - set all flags to false
            log.warn("Patient {} - NOT in initial population", patientMeasureData.getPatientId());
            patientMeasureData.setEligibleEncounter(false);
            patientMeasureData.setC2Denominator(false);
            patientMeasureData.setDenominatorExcluded(false); // DENEX only applies to IPOP patients
            patientMeasureData.setC2Numerator(false);
            patientMeasureData.setReceivedRequiredIntervention(false);
            return;
        }

        log.info("Patient {} - IN initial population (IPP = true)", patientMeasureData.getPatientId());

        // Step 2: Check exclusions (DENEX)
        // Exclusions are tracked separately but do NOT reduce the denominator
        boolean isExcluded = isExcluded(patientMeasureData, periodStart, periodEnd);
        patientMeasureData.setDenominatorExcluded(isExcluded);

        boolean denominator = inInitialPopulation;
        patientMeasureData.setC2Denominator(denominator);
        patientMeasureData.setEligibleEncounter(denominator); // Has eligible encounter if in denominator

        log.info("Patient {} - Denominator: {} (IPOP: {}, Excluded: {})", 
                patientMeasureData.getPatientId(), denominator, inInitialPopulation, isExcluded);

        // Step 4: Numerator (NUM)
        // NUM = DENOM patients who had fall risk screening (LOINC 73830-2 or 73832-0) during/linked to qualifying encounter
        boolean numerator = false;
        if (denominator) {
            numerator = hasRequiredScreening(patientMeasureData, periodStart, periodEnd);
            log.info("Patient {} - Numerator: {} (in denominator and has required screening)", 
                    patientMeasureData.getPatientId(), numerator);
        } else {
            log.info("Patient {} - Numerator: false (not in denominator)", patientMeasureData.getPatientId());
        }
        
        patientMeasureData.setC2Numerator(numerator);
        patientMeasureData.setReceivedRequiredIntervention(numerator);
        
        log.info("=== Patient {} evaluation complete - IPOP: {}, DENOM: {}, DENEX: {}, NUMER: {} ===", 
                patientMeasureData.getPatientId(), inInitialPopulation, denominator, isExcluded, numerator);
    }

    /**
     * Check if patient is in initial population (IPP)
     * Requirement: "Patients aged 65 years and older at the start of the measurement period
     * with a visit during the measurement period"
     * <p>
     * Logic: Age ≥ 65 at period start AND has qualifying encounter during measurement period
     */
    public static boolean isInInitialPopulation(PatientMeasureData patient, LocalDate periodStart, LocalDate periodEnd) {
        if (patient == null) return false;

        boolean hasEncounter = hasQualifyingEncounter(patient, periodStart, periodEnd);
        if (!hasEncounter) {
            log.debug("Patient {} - no qualifying encounter during measurement period", patient.getPatientId());
            return false;
        }
        List<PatientMeasureData.EncounterData> encounters = patient.getEncounters();
        LocalDateTime lastEncounterDate;
        if (encounters != null && !encounters.isEmpty()) {
            PatientMeasureData.EncounterData lastEncounter = encounters.get(encounters.size() - 1);
            lastEncounterDate = lastEncounter.getStartDate();
            boolean ageOk = checkAgeRequirement(patient, lastEncounterDate.toLocalDate());

            if (!ageOk) {

                log.debug("Patient {} - fails age requirement", patient.getPatientId());
                return false;
            }
        }


        log.info("Patient {} - included in IPP (age >= {} and qualifying encounter found)", patient.getPatientId(), MIN_AGE_FOR_MEASURE);
        return true;
    }

    /**
     * Check if patient meets age requirement (≥ 65 years at start of measurement period)
     */
    // --- Age check: age at measurement period START -----------------
    private static boolean checkAgeRequirement(PatientMeasureData patient, LocalDate periodStart) {
        String birthDateStr = null;
        if (patient.getPersonalDetailsData() != null &&
                patient.getPersonalDetailsData().getResponse() != null &&
                patient.getPersonalDetailsData().getResponse().getPatientInformation() != null) {
            var infoMap = patient.getPersonalDetailsData().getResponse().getPatientInformation();
            var info = infoMap.values().stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (info != null) birthDateStr = info.getBirthDate();
        }

        if (birthDateStr == null || birthDateStr.isBlank()) {
            log.debug("Patient {} - missing birth date", patient.getPatientId());
            return false;
        }

            LocalDate birthDate = parseDate(birthDateStr);
        if (birthDate == null || periodStart == null) {
            log.warn("Patient {} - unable to parse birthDate or periodStart: birthDate={}, periodStart={}", patient.getPatientId(), birthDateStr, periodStart);
            return false;
        }

        int ageAtStart = (int) calculateExactAge(birthDate, periodStart);
        boolean ok = ageAtStart >= MIN_AGE_FOR_MEASURE;
        log.info("age of patient at start :::", ageAtStart);
        log.debug("Patient {} - birthDate={}, ageAtStart={}, meetsAgeRequirement={}", patient.getPatientId(), birthDate, ageAtStart, ok);
        return ok;
    }

    public static double calculateExactAge(LocalDate birthDate, LocalDate periodStart) {
        // Calculate the total number of days between the two dates
        long daysBetween = ChronoUnit.DAYS.between(birthDate, periodStart);

        // A precise average number of days in a year (accounting for leap years)
        // This is often used for financial or high-precision age calculations.
        final double DAYS_IN_YEAR = 365.2425;

        // Calculate the age in years as a double
        double exactAge = daysBetween / DAYS_IN_YEAR;

        return exactAge;
    }

    /**
     * Check if patient has qualifying encounter with correct codes within measurement period
     * Qualifying encounters: Office visits, preventive visits, annual wellness visits with correct CPT codes
     */
    // --- Encounters: must be coded and in qualifying set, and not in exclusion sets ---
    private static boolean hasQualifyingEncounter(PatientMeasureData patient, LocalDate periodStart, LocalDate periodEnd) {
        var encounters = patient.getEncounters();
        if (encounters == null || encounters.isEmpty()) {
            log.debug("Patient {} - no encounters", patient.getPatientId());
            return false;
        }

        for (var e : encounters) {
            if (e == null || e.getStartDate() == null) continue;
            LocalDate encDate = e.getStartDate().toLocalDate();
            if (encDate.isBefore(periodStart) || encDate.isAfter(periodEnd)) {
                continue;
            }

            // Try to get the code(s) from encounter. Some systems store a code + system, or multiple codes.
            // Prefer the code field; if null, try any code list the DTO exposes (expand if needed).
            String code = e.getCode();  // assumed to be the code string (CPT or SNOMED)
            if (code != null && !code.isBlank()) {
                if (isQualifyingEncounterCode(code)) {
                    log.debug("Patient {} - encounter {} on {} accepted (code={})", patient.getPatientId(), e.getId(), encDate, code);
                    return true;
                } else {
                    log.debug("Patient {} - encounter {} on {} rejected by code {} (not in qualifying set or in exclusion)", patient.getPatientId(), e.getId(), encDate, code);
                }
            } else {
                // If there is no code we cannot accept it. Do NOT rely on free-text type/description.
                log.debug("Patient {} - encounter {} on {} has no code; skipping (no free-text fallback)", patient.getPatientId(), e.getId(), encDate);
            }
        }

        return false;
    }

    /**
     * Check if encounter type/code is qualifying for CMS139
     * Accepts office visits, preventive visits, annual wellness visits
     * Must have either a qualifying CPT code OR a qualifying type description
     */

    // --- Single coded check (normalizes & checks exclusions first) ----------------
    private static boolean isQualifyingEncounterCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) return false;
        String code = normalizeCode(rawCode);

        // Exclusions
        if (ED_EXCLUSION_CODES.contains(code)) return false;
        if (HOSPICE_CODES.contains(code)) return false;
        if (PALLIATIVE_CODES.contains(code)) return false;

        // Acceptance only if part of the qualifying set
        return QUALIFYING_ENCOUNTER_CODES.contains(code);
    }

    private static String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.trim().toUpperCase();
        // Strip common prefixes (SCT- for SNOMED, LC- for LOINC)
        if (normalized.startsWith("SCT-")) {
            normalized = normalized.substring(4);
        } else if (normalized.startsWith("LC-")) {
            normalized = normalized.substring(3);
        }
        return normalized;
    }



    private static class CodedElement {
        private final String code;
        private final String codeSystem;
        private final String display;
        private final String source;
        private final LocalDate startDate; // Start date associated with this coded element
        private final LocalDate endDate; // End date associated with this coded element (null if ongoing/unknown)

        public CodedElement(String code, String codeSystem, String display, String source, LocalDate startDate, LocalDate endDate) {
            this.code = code;
            this.codeSystem = codeSystem;
            this.display = display;
            this.source = source;
            this.startDate = startDate;
            this.endDate = endDate;
        }

        public String getCode() {
            return code;
        }

        public String getCodeSystem() {
            return codeSystem;
        }

        public String getDisplay() {
            return display;
        }

        public String getSource() {
            return source;
        }

        public LocalDate getStartDate() {
            return startDate;
        }

        public LocalDate getEndDate() {
            return endDate;
        }

        // Backward compatibility - returns startDate
        @Deprecated
        public LocalDate getDate() {
            return startDate;
        }
    }

    /**
     * Extract ALL coded elements from QRDA-I data
     * Includes: encounters, assessments, interventions with SnomedCodes, LoincCodes
     */
    private static List<CodedElement> getAllCodesFromQRDA(PatientMeasureData patientMeasureData) {
        List<CodedElement> allCodes = new ArrayList<>();

        // 1. Extract codes from encounters
        if (patientMeasureData.getEncounters() != null) {
            for (PatientMeasureData.EncounterData encounter : patientMeasureData.getEncounters()) {
                if (encounter == null) continue;

                // Extract dates from encounter
                LocalDate encounterStartDate = null;
                LocalDate encounterEndDate = null;
                if (encounter.getStartDate() != null) {
                    encounterStartDate = encounter.getStartDate().toLocalDate();
                }
                if (encounter.getEndDate() != null) {
                    encounterEndDate = encounter.getEndDate().toLocalDate();
                }

                // Encounter code
                if (encounter.getCode() != null && !encounter.getCode().isBlank()) {
                    allCodes.add(new CodedElement(
                            encounter.getCode(),
                            encounter.getCodeSystem(),
                            encounter.getDescription(),
                            "Encounter-" + encounter.getId(),
                            encounterStartDate,
                            encounterEndDate
                    ));
                }

                // Encounter description as display text
                if (encounter.getDescription() != null && !encounter.getDescription().isBlank()) {
                    allCodes.add(new CodedElement(
                            null,
                            null,
                            encounter.getDescription(),
                            "Encounter-Description-" + encounter.getId(),
                            encounterStartDate,
                            encounterEndDate
                    ));
                }

                // Encounter status as display text
                if (encounter.getStatus() != null && !encounter.getStatus().isBlank()) {
                    allCodes.add(new CodedElement(
                            null,
                            null,
                            encounter.getStatus(),
                            "Encounter-Status-" + encounter.getId(),
                            encounterStartDate,
                            encounterEndDate
                    ));
                }
            }
        }

        // 2. Extract codes from FormResponse -> Assessment and Intervention
        if (patientMeasureData.getFormResponses() != null) {
            for (FormResponse formResponse : patientMeasureData.getFormResponses()) {
                if (formResponse == null) continue;

                // Check Assessment codes
                if (formResponse.getAssessment() != null) {
                    for (Map.Entry<String, CodeSection> entry : formResponse.getAssessment().entrySet()) {
                        String entryKey = entry.getKey();
                        CodeSection codeSection = entry.getValue();
                        if (codeSection == null) continue;

                        // Extract SnomedCodes from Assessment
                        if (codeSection.getSnomedCodes() != null) {
                            List<SnomedCode> snomedCodes = parseSnomedCodes(codeSection.getSnomedCodes());
                            for (SnomedCode snomedCode : snomedCodes) {
                                if (snomedCode == null) continue;
                                
                                // Extract dates from SnomedCode
                                LocalDate snomedStartDate = null;
                                LocalDate snomedEndDate = null;
                                if (snomedCode.getStartDate() != null && !snomedCode.getStartDate().isBlank()) {
                                    LocalDateTime dateTime = parseDateTime(snomedCode.getStartDate());
                                    if (dateTime != null) {
                                        snomedStartDate = dateTime.toLocalDate();
                                    }
                                }
                                // Extract end date - null or empty string means ongoing/unknown
                                if (snomedCode.getEndDate() != null && !snomedCode.getEndDate().isBlank()) {
                                    LocalDateTime dateTime = parseDateTime(snomedCode.getEndDate());
                                    if (dateTime != null) {
                                        snomedEndDate = dateTime.toLocalDate();
                                    }
                                } else {
                                    // null or empty endDate means ongoing/unknown - leave as null
                                    snomedEndDate = null;
                                }
                                
                                // Add code
                                if (snomedCode.getCode() != null && !snomedCode.getCode().isBlank()) {
                                    allCodes.add(new CodedElement(
                                            snomedCode.getCode(),
                                            "SNOMED-CT",
                                            snomedCode.getDescription(),
                                            "Assessment-SnomedCode-" + entryKey,
                                            snomedStartDate,
                                            snomedEndDate
                                    ));
                                }
                                
                                // Add conceptId
                                if (snomedCode.getConceptId() != null && !snomedCode.getConceptId().isBlank()) {
                                    allCodes.add(new CodedElement(
                                            snomedCode.getConceptId(),
                                            "SNOMED-CT",
                                            snomedCode.getDescription(),
                                            "Assessment-SnomedConceptId-" + entryKey,
                                            snomedStartDate,
                                            snomedEndDate
                                    ));
                                }
                                
                                // Add description as display
                                if (snomedCode.getDescription() != null && !snomedCode.getDescription().isBlank()) {
                                    allCodes.add(new CodedElement(
                                            null,
                                            null,
                                            snomedCode.getDescription(),
                                            "Assessment-SnomedDescription-" + entryKey,
                                            snomedStartDate,
                                            snomedEndDate
                                    ));
                                }
                            }
                        }

                        // Extract LoincCodes from Assessment
                        if (codeSection.getLoincCodes() != null) {
                            List<LoincCode> loincCodes = parseLoincCodesFromFormResponse(codeSection.getLoincCodes());
                            for (LoincCode loincCode : loincCodes) {
                                if (loincCode == null) continue;
                                
                                // Extract dates from LoincCode
                                LocalDate loincStartDate = null;
                                LocalDate loincEndDate = null;
                                if (loincCode.getStartDate() != null && !loincCode.getStartDate().isBlank()) {
                                    LocalDateTime dateTime = parseDateTime(loincCode.getStartDate());
                                    if (dateTime != null) {
                                        loincStartDate = dateTime.toLocalDate();
                                    }
                                }
                                // Extract end date - null or empty string means ongoing/unknown
                                if (loincCode.getEndDate() != null && !loincCode.getEndDate().isBlank()) {
                                    LocalDateTime dateTime = parseDateTime(loincCode.getEndDate());
                                    if (dateTime != null) {
                                        loincEndDate = dateTime.toLocalDate();
                                    }
                                } else {
                                    // null or empty endDate means ongoing/unknown - leave as null
                                    loincEndDate = null;
                                }
                                
                                // Add code
                                if (loincCode.getCode() != null && !loincCode.getCode().isBlank()) {
                                    allCodes.add(new CodedElement(
                                            loincCode.getCode(),
                                            "LOINC",
                                            loincCode.getDescription(),
                                            "Assessment-LoincCode-" + entryKey,
                                            loincStartDate,
                                            loincEndDate
                                    ));
                                }
                                
                                // Add description as display
                                if (loincCode.getDescription() != null && !loincCode.getDescription().isBlank()) {
                                    allCodes.add(new CodedElement(
                                            null,
                                            null,
                                            loincCode.getDescription(),
                                            "Assessment-LoincDescription-" + entryKey,
                                            loincStartDate,
                                            loincEndDate
                                    ));
                                }
                            }
                        }
                    }
                }

                // Check Intervention codes
                if (formResponse.getIntervention() != null) {
                    for (Map.Entry<String, CodeSection> entry : formResponse.getIntervention().entrySet()) {
                        String entryKey = entry.getKey();
                        CodeSection codeSection = entry.getValue();
                        if (codeSection == null) continue;

                        // Extract SnomedCodes from Intervention
                        if (codeSection.getSnomedCodes() != null) {
                            List<SnomedCode> snomedCodes = parseSnomedCodes(codeSection.getSnomedCodes());
                            for (SnomedCode snomedCode : snomedCodes) {
                                if (snomedCode == null) continue;
                                
                                // Extract dates from SnomedCode
                                LocalDate snomedStartDate = null;
                                LocalDate snomedEndDate = null;
                                if (snomedCode.getStartDate() != null && !snomedCode.getStartDate().isBlank()) {
                                    String startDateStr = snomedCode.getStartDate();
                                    LocalDateTime dateTime = parseDateTime(startDateStr);
                                    if (dateTime != null) {
                                        snomedStartDate = dateTime.toLocalDate();
                                        log.debug("Patient {} - Extracted startDate {} from intervention SnomedCode {} (startDate string: {})",
                                                patientMeasureData.getPatientId(), snomedStartDate, snomedCode.getCode(), startDateStr);
                                    } else {
                                        log.warn("Patient {} - Failed to parse startDate from intervention SnomedCode {} (startDate string: {})",
                                                patientMeasureData.getPatientId(), snomedCode.getCode(), startDateStr);
                                    }
                                } else {
                                    log.debug("Patient {} - Intervention SnomedCode {} has no startDate",
                                            patientMeasureData.getPatientId(), snomedCode.getCode());
                                }
                                if (snomedCode.getEndDate() != null && !snomedCode.getEndDate().isBlank()) {
                                    String endDateStr = snomedCode.getEndDate();
                                    LocalDateTime dateTime = parseDateTime(endDateStr);
                                    if (dateTime != null) {
                                        snomedEndDate = dateTime.toLocalDate();
                                        log.debug("Patient {} - Extracted endDate {} from intervention SnomedCode {} (endDate string: {})",
                                                patientMeasureData.getPatientId(), snomedEndDate, snomedCode.getCode(), endDateStr);
                                    }
                                }
                                
                                // Add code
                                if (snomedCode.getCode() != null && !snomedCode.getCode().isBlank()) {
                                    allCodes.add(new CodedElement(
                                            snomedCode.getCode(),
                                            "SNOMED-CT",
                                            snomedCode.getDescription(),
                                            "Intervention-SnomedCode-" + entryKey,
                                            snomedStartDate,
                                            snomedEndDate
                                    ));
                                }
                                
                                // Add conceptId
                                if (snomedCode.getConceptId() != null && !snomedCode.getConceptId().isBlank()) {
                                    allCodes.add(new CodedElement(
                                            snomedCode.getConceptId(),
                                            "SNOMED-CT",
                                            snomedCode.getDescription(),
                                            "Intervention-SnomedConceptId-" + entryKey,
                                            snomedStartDate,
                                            snomedEndDate
                                    ));
                                }
                                
                                // Add description as display
                                if (snomedCode.getDescription() != null && !snomedCode.getDescription().isBlank()) {
                                    allCodes.add(new CodedElement(
                                            null,
                                            null,
                                            snomedCode.getDescription(),
                                            "Intervention-SnomedDescription-" + entryKey,
                                            snomedStartDate,
                                            snomedEndDate
                                    ));
                                }
                            }
                        }

                        // Extract LoincCodes from Intervention
                        if (codeSection.getLoincCodes() != null) {
                            List<LoincCode> loincCodes = parseLoincCodesFromFormResponse(codeSection.getLoincCodes());
                            for (LoincCode loincCode : loincCodes) {
                                if (loincCode == null) continue;
                                
                                // Extract dates from LoincCode
                                LocalDate loincStartDate = null;
                                LocalDate loincEndDate = null;
                                if (loincCode.getStartDate() != null && !loincCode.getStartDate().isBlank()) {
                                    String startDateStr = loincCode.getStartDate();
                                    LocalDateTime dateTime = parseDateTime(startDateStr);
                                    if (dateTime != null) {
                                        loincStartDate = dateTime.toLocalDate();
                                        log.debug("Patient {} - Extracted startDate {} from intervention LoincCode {} (startDate string: {})",
                                                patientMeasureData.getPatientId(), loincStartDate, loincCode.getCode(), startDateStr);
                                    } else {
                                        log.warn("Patient {} - Failed to parse startDate from intervention LoincCode {} (startDate string: {})",
                                                patientMeasureData.getPatientId(), loincCode.getCode(), startDateStr);
                                    }
                                } else {
                                    log.debug("Patient {} - Intervention LoincCode {} has no startDate",
                                            patientMeasureData.getPatientId(), loincCode.getCode());
                                }
                                if (loincCode.getEndDate() != null && !loincCode.getEndDate().isBlank()) {
                                    String endDateStr = loincCode.getEndDate();
                                    LocalDateTime dateTime = parseDateTime(endDateStr);
                                    if (dateTime != null) {
                                        loincEndDate = dateTime.toLocalDate();
                                        log.debug("Patient {} - Extracted endDate {} from intervention LoincCode {} (endDate string: {})",
                                                patientMeasureData.getPatientId(), loincEndDate, loincCode.getCode(), endDateStr);
                                    }
                                }
                                
                                // Add code
                                if (loincCode.getCode() != null && !loincCode.getCode().isBlank()) {
                                    allCodes.add(new CodedElement(
                                            loincCode.getCode(),
                                            "LOINC",
                                            loincCode.getDescription(),
                                            "Intervention-LoincCode-" + entryKey,
                                            loincStartDate,
                                            loincEndDate
                                    ));
                                }
                                
                                // Add description as display
                                if (loincCode.getDescription() != null && !loincCode.getDescription().isBlank()) {
                                    allCodes.add(new CodedElement(
                                            null,
                                            null,
                                            loincCode.getDescription(),
                                            "Intervention-LoincDescription-" + entryKey,
                                            loincStartDate,
                                            loincEndDate
                                    ));
                                }
                            }
                        }
                    }
                }
            }
        }

        return allCodes;
    }

    /**
     * Parse SnomedCodes from Object (can be List or single object)
     */
    @SuppressWarnings("unchecked")
    private static List<SnomedCode> parseSnomedCodes(Object snomedCodesObj) {
        List<SnomedCode> snomedCodes = new ArrayList<>();

        if (snomedCodesObj == null) {
            return snomedCodes;
        }

        if (snomedCodesObj instanceof List) {
            for (Object item : (List<?>) snomedCodesObj) {
                if (item instanceof Map) {
                    SnomedCode snomedCode = objectMapper.convertValue(item, SnomedCode.class);
                    if (snomedCode != null) {
                        snomedCodes.add(snomedCode);
                    }
                } else if (item instanceof SnomedCode) {
                    snomedCodes.add((SnomedCode) item);
                }
            }
        } else if (snomedCodesObj instanceof Map) {
            SnomedCode snomedCode = objectMapper.convertValue(snomedCodesObj, SnomedCode.class);
            if (snomedCode != null) {
                snomedCodes.add(snomedCode);
            }
        }

        return snomedCodes;
    }

    /**
     * Check if patient should be excluded from denominator (DENEX)
     * Requirement: "Exclude patients who are in hospice care for any part of the measurement period"
     * 
     * Simple Logic: DENEX = TRUE if ANY hospice code appears anywhere in QRDA
     */
    private static boolean isExcluded(PatientMeasureData patientMeasureData, LocalDate periodStart, LocalDate periodEnd) {
        // Loop through ALL coded elements from QRDA-I
        List<CodedElement> allCodes = getAllCodesFromQRDA(patientMeasureData);

        for (CodedElement c : allCodes) {
            String code = c.getCode();
            String display = c.getDisplay();
            LocalDate startDate = c.getStartDate();
            LocalDate endDate = c.getEndDate();
            String source = c.getSource();
            
            // Log all hospice-related elements for debugging
            boolean isHospiceRelated = false;
            if (code != null && !code.isBlank()) {
                String normalizedCode = normalizeCode(code);
                if (HOSPICE_CODES.contains(normalizedCode)) {
                    isHospiceRelated = true;
                    log.debug("Patient {} - Found potential hospice code: {} (normalized: {}) in {} with startDate: {}, endDate: {}",
                            patientMeasureData.getPatientId(), code, normalizedCode, source, startDate, endDate);
                }
            }
            if (display != null && display.toUpperCase().contains("HOSPICE")) {
                isHospiceRelated = true;
                log.debug("Patient {} - Found potential hospice in display: '{}' in {} with startDate: {}, endDate: {}",
                        patientMeasureData.getPatientId(), display, source, startDate, endDate);
            }
            
            // Check by code first
            if (code != null && !code.isBlank()) {
                String normalizedCode = normalizeCode(code);
                
                // Hospice care value set (full list)
                if (HOSPICE_CODES.contains(normalizedCode)) {
                    if (isHospiceDateRangeOverlapping(startDate, endDate, periodStart, periodEnd)) {
                        log.info("Patient {} - EXCLUDED: Found hospice code {} (original: {}) in {} with date range (start: {}, end: {}) overlapping measurement period ({} to {})",
                                patientMeasureData.getPatientId(), normalizedCode, code, source, startDate, endDate, periodStart, periodEnd);
                        return true;
                    } else {
                        log.info("Patient {} - Found hospice code {} (original: {}) in {} with date range (start: {}, end: {}) NOT overlapping measurement period ({} to {}), NOT excluding",
                                patientMeasureData.getPatientId(), normalizedCode, code, source, startDate, endDate, periodStart, periodEnd);
                    }
                }
            }

            // Check by display text (for cases like "Hospice Care Ambulatory")
            if (display != null && display.toUpperCase().contains("HOSPICE")) {
                if (isHospiceDateRangeOverlapping(startDate, endDate, periodStart, periodEnd)) {
                    log.info("Patient {} - EXCLUDED: Found hospice keyword in display text '{}' from {} with date range (start: {}, end: {}) overlapping measurement period ({} to {})",
                            patientMeasureData.getPatientId(), display, source, startDate, endDate, periodStart, periodEnd);
                    return true;
                } else {
                    log.info("Patient {} - Found hospice keyword in display text '{}' from {} with date range (start: {}, end: {}) NOT overlapping measurement period ({} to {}), NOT excluding",
                            patientMeasureData.getPatientId(), display, source, startDate, endDate, periodStart, periodEnd);
                }
            }
        }

        log.debug("Patient {} - No hospice exclusions found overlapping measurement period", patientMeasureData.getPatientId());
        return false;
    }


    private static boolean isHospiceDateRangeOverlapping(LocalDate startDate, LocalDate endDate, 
                                                          LocalDate periodStart, LocalDate periodEnd) {
        if (startDate == null) {
            log.debug("Hospice care has no start date, cannot determine overlap");
            return false;
        }
        
        if (!startDate.isBefore(periodStart) && !startDate.isAfter(periodEnd)) {
            log.debug("Hospice start date {} is within measurement period", startDate);
            return true;
        }
        
        // Case 2: Start date is before measurement period
        if (startDate.isBefore(periodStart)) {
            // If end date is null/unknown → still active, exclude
            if (endDate == null) {
                log.debug("Hospice started {} before measurement period but has no end date (still active), excluding", startDate);
                return true;
            }
            // If end date is after measurement period start → overlaps, exclude
            if (!endDate.isBefore(periodStart)) {
                log.debug("Hospice date range ({} to {}) overlaps with measurement period (starts before, ends during/after)", 
                        startDate, endDate);
                return true;
            }
            return false;
        }
        
        return false;
    }


    private static boolean hasRequiredScreening(PatientMeasureData patientMeasureData, LocalDate periodStart, LocalDate periodEnd) {
        List<FormResponse> formResponses = patientMeasureData.getFormResponses();

        if (formResponses == null || formResponses.isEmpty()) {
            log.warn("Patient {} - No FormResponse found", patientMeasureData.getPatientId());
            return false;
        }

        log.info("Patient {} - Checking {} FormResponse(s) for fall risk screening (LOINC 73830-2 or 73832-0) within measurement period",
                patientMeasureData.getPatientId(), formResponses.size());

        // Check FormResponse objects for fall risk screening assessments
        for (FormResponse formResponse : formResponses) {
            if (formResponse == null || formResponse.getAssessment() == null || formResponse.getAssessment().isEmpty()) {
                continue;
            }

            // Iterate through Assessment entries in FormResponse
            for (Map.Entry<String, CodeSection> assessmentEntry : formResponse.getAssessment().entrySet()) {
                String assessmentId = assessmentEntry.getKey();
                CodeSection codeSection = assessmentEntry.getValue();

                if (codeSection == null || codeSection.getLoincCodes() == null) {
                    continue;
                }

                // Parse LoincCodes (can be List or single object)
                List<LoincCode> loincCodes = parseLoincCodesFromFormResponse(codeSection.getLoincCodes());

                for (LoincCode loincCode : loincCodes) {
                    if (loincCode == null || loincCode.getCode() == null) {
                        continue;
                    }

                    String code = loincCode.getCode();
                    // Normalize code (remove LC- prefix if present for comparison)
                    String normalizedCode = code.startsWith("LC-") ? code.substring(3) : code;

                    // Check if assessment code matches fall risk screening LOINC codes
                    boolean isFallRiskScreening = FALL_RISK_SCREENING_LOINC_CODES.contains(code) ||
                            FALL_RISK_SCREENING_LOINC_CODES.contains(normalizedCode) ||
                            FALL_RISK_SCREENING_LOINC_CODES.stream()
                                    .anyMatch(screeningCode -> {
                                        String normalizedScreeningCode = screeningCode.startsWith("LC-") ? screeningCode.substring(3) : screeningCode;
                                        return normalizedCode.equals(normalizedScreeningCode) || 
                                               code.contains(normalizedScreeningCode) || 
                                               normalizedScreeningCode.contains(normalizedCode);
                                    });

                    if (!isFallRiskScreening) {
                        log.debug("Patient {} - Assessment {} is not a fall risk screening (code: {})", 
                                patientMeasureData.getPatientId(), assessmentId, code);
                        continue;
                    }

                    // Check if assessment is within measurement period
                    // Requirement: "at least once within the measurement period"
                    LocalDateTime assessmentTime = null;
                    if (loincCode.getStartDate() != null) {
                        assessmentTime = parseDateTime(loincCode.getStartDate());
                    }
                    
                    if (assessmentTime == null) {
                        log.debug("Patient {} - Assessment {} has no valid time, skipping",
                                patientMeasureData.getPatientId(), assessmentId);
                        continue;
                    }

                    LocalDate assessmentDate = assessmentTime.toLocalDate();

                    if (assessmentDate.isBefore(periodStart) || assessmentDate.isAfter(periodEnd)) {
                        log.debug("Patient {} - Assessment {} on {} is outside measurement period ({} to {})",
                                patientMeasureData.getPatientId(), assessmentId, assessmentDate, periodStart, periodEnd);
                        continue;
                    }

                    // Found fall risk screening within measurement period
                    log.info("Patient {} - Found fall risk screening {} (code: {}) on {} within measurement period ({} to {})",
                            patientMeasureData.getPatientId(), assessmentId, code, assessmentDate, periodStart, periodEnd);
                            return true;
                }
            }
        }

        log.warn("Patient {} - No fall risk screening found within measurement period ({} to {})",
                patientMeasureData.getPatientId(), periodStart, periodEnd);
        return false;
    }


     // Parse date string to LocalDate
    private static LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }

        try {
            if (dateStr.contains("-")) {
                return LocalDate.parse(dateStr, dateFormatter);
            } else if (dateStr.length() == 8) {
                int year = Integer.parseInt(dateStr.substring(0, 4));
                int month = Integer.parseInt(dateStr.substring(4, 6));
                int day = Integer.parseInt(dateStr.substring(6, 8));
                return LocalDate.of(year, month, day);
            }
        } catch (Exception e) {
            log.debug("Error parsing date: {}", dateStr, e);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<LoincCode> parseLoincCodesFromFormResponse(Object loincCodesObj) {
        List<LoincCode> loincCodes = new ArrayList<>();
        
        if (loincCodesObj == null) {
            return loincCodes;
        }
        
        if (loincCodesObj instanceof List) {
            for (Object item : (List<?>) loincCodesObj) {
                if (item instanceof Map) {
                    LoincCode loincCode = objectMapper.convertValue(item, LoincCode.class);
                    if (loincCode != null) {
                        loincCodes.add(loincCode);
                    }
                } else if (item instanceof LoincCode) {
                    loincCodes.add((LoincCode) item);
                }
            }
        } else if (loincCodesObj instanceof Map) {
            LoincCode loincCode = objectMapper.convertValue(loincCodesObj, LoincCode.class);
            if (loincCode != null) {
                loincCodes.add(loincCode);
            }
        }
        
        return loincCodes;
    }

    public static void extractEncounters(PatientMeasureData patientMeasureData) {
        AppointmentData appointmentData = patientMeasureData.getAppointmentData();
        if (appointmentData == null || appointmentData.getAppointments() == null) {
            log.warn("Patient {} - No appointment data available", patientMeasureData.getPatientId());
            patientMeasureData.setEncounters(new ArrayList<>());
            return;
        }

        int totalAppointments = appointmentData.getAppointments().size();
        log.info("Patient {} - Extracting encounters from {} appointments", 
                patientMeasureData.getPatientId(), totalAppointments);
        
        if (totalAppointments == 0) {
            log.warn("Patient {} - AppointmentData exists but has 0 appointments", patientMeasureData.getPatientId());
            patientMeasureData.setEncounters(new ArrayList<>());
            return;
        }

        List<PatientMeasureData.EncounterData> encounters = appointmentData.getAppointments().stream()
                .map(appointment -> {
                    PatientMeasureData.EncounterData encounter = new PatientMeasureData.EncounterData();
                    encounter.setId(String.valueOf(appointment.getAppointment_id()));
                    
                    // Extract CPT code from category (like QRDA does)
                    String cptCode = extractCptCodeFromCategory(appointment);
                    encounter.setCode(cptCode != null ? cptCode : appointment.getType());
                    encounter.setDescription(appointment.getType());
                    encounter.setStatus(appointment.getAppointment_status());
                    
                    // Parse date/time - handle epoch timestamps like QRDA does
                    if (appointment.getDate_time() != null) {
                        LocalDateTime startDate = parseDateTime(appointment.getDate_time());
                        encounter.setStartDate(startDate);
                        if (startDate != null) {
                            log.debug("Patient {} - Parsed appointment {} start date: {} (from epoch: {})", 
                                    patientMeasureData.getPatientId(), appointment.getAppointment_id(), 
                                    startDate, appointment.getDate_time());
                        } else {
                            log.warn("Patient {} - Failed to parse appointment {} start date from: {}", 
                                    patientMeasureData.getPatientId(), appointment.getAppointment_id(), 
                                    appointment.getDate_time());
                        }
                    }
                    if (appointment.getEnd_date_time() != null) {
                        LocalDateTime endDate = parseDateTime(appointment.getEnd_date_time());
                        encounter.setEndDate(endDate);
                    }
                    
                    return encounter;
                })
                .filter(e -> {
                    if (e.getStartDate() == null) {
                        log.debug("Patient {} - Filtering out encounter {} (no valid start date)", 
                                patientMeasureData.getPatientId(), e.getId());
                        return false;
                    }
                    return true;
                })
                .toList();

        int validEncounters = encounters.size();
        int invalidCount = totalAppointments - validEncounters;
        log.info("Patient {} - Extracted {} valid encounters (with start dates) out of {} appointments ({} filtered out due to missing/invalid dates)", 
                patientMeasureData.getPatientId(), validEncounters, totalAppointments, invalidCount);
        
        if (validEncounters == 0 && totalAppointments > 0) {
            log.error("Patient {} - WARNING: All {} appointments were filtered out due to missing/invalid dates! " +
                    "This suggests a date parsing issue. Check appointment date_time format.", 
                    patientMeasureData.getPatientId(), totalAppointments);
        }
        
        patientMeasureData.setEncounters(encounters);
    }

    /**
     * Extract CPT code from appointment category (like QRDA does)
     * QRDA determines CPT code based on category name
     */
    private static String extractCptCodeFromCategory(Appointment appointment) {
        if (appointment.getCategory() == null || appointment.getCategory().isEmpty()) {
            return null;
        }
        
        // QRDA logic: Check category name to determine CPT code
        for (AppointmentCategory category : appointment.getCategory()) {
            if (category != null && category.getName() != null) {
                String categoryName = category.getName().trim();
                if ("Initial Evaluation".equalsIgnoreCase(categoryName)) {
                    return "99203";
                } else if ("Follow-up".equalsIgnoreCase(categoryName) || 
                          "follow-up".equalsIgnoreCase(categoryName)) {
                    return "99213";
                }
            }
        }
        
        // Default CPT code (like QRDA uses)
        return "99213";
    }

    /**
     * Parse datetime string to LocalDateTime
     * Handles epoch timestamps exactly like QRDA package does
     * QRDA uses: Long.parseLong(epochStr) * 1000L to convert seconds to milliseconds
     */
    private static LocalDateTime parseDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) {
            return null;
        }

        try {
            // Check if it's an epoch timestamp (numeric string) - QRDA approach
            // EHR returns epoch timestamps in SECONDS (10 digits), need to multiply by 1000
            if (dateTimeStr.matches("\\d+")) {
                try {
                    long epochSeconds = Long.parseLong(dateTimeStr);
                    // QRDA multiplies by 1000 to convert seconds to milliseconds
                    long epochMillis = epochSeconds * 1000L;
                    
                    // Convert to LocalDateTime using system default zone (like QRDA does)
                    java.time.ZoneId zoneId = java.time.ZoneId.systemDefault();
                    java.time.Instant instant = java.time.Instant.ofEpochMilli(epochMillis);
                    return LocalDateTime.ofInstant(instant, zoneId);
                } catch (Exception e) {
                    log.debug("Failed to parse as epoch timestamp: {}", dateTimeStr, e);
                }
            }
            
            // Try ISO 8601 format
            if (dateTimeStr.contains("T")) {
                String isoStr = dateTimeStr;
                if (isoStr.contains("Z") || isoStr.contains("+") || (isoStr.lastIndexOf("-") > 10)) {
                    try {
                        return java.time.ZonedDateTime.parse(isoStr, 
                            DateTimeFormatter.ISO_DATE_TIME).toLocalDateTime();
                    } catch (Exception e) {
                        // Fall through
                    }
                }
                if (isoStr.contains(".")) {
                    isoStr = isoStr.substring(0, isoStr.indexOf("."));
                }
                if (isoStr.endsWith("Z")) {
                    isoStr = isoStr.substring(0, isoStr.length() - 1);
                } else if (isoStr.contains("+") || (isoStr.lastIndexOf("-") > 10)) {
                    int tzIndex = isoStr.indexOf("+");
                    if (tzIndex == -1) {
                        int lastDash = isoStr.lastIndexOf("-");
                        if (lastDash > 10) {
                            isoStr = isoStr.substring(0, lastDash);
                        }
                    } else {
                        isoStr = isoStr.substring(0, tzIndex);
                    }
                }
                return LocalDateTime.parse(isoStr, dateTimeFormatter);
            } else if (dateTimeStr.contains("-") && dateTimeStr.length() == 10) {
                return LocalDate.parse(dateTimeStr, dateFormatter).atStartOfDay();
            } else if (dateTimeStr.length() == 8) {
                // yyyyMMdd format
                int year = Integer.parseInt(dateTimeStr.substring(0, 4));
                int month = Integer.parseInt(dateTimeStr.substring(4, 6));
                int day = Integer.parseInt(dateTimeStr.substring(6, 8));
                return LocalDateTime.of(year, month, day, 0, 0);
            } else if (dateTimeStr.length() >= 14) {
                // yyyyMMddHHmmss format
                int year = Integer.parseInt(dateTimeStr.substring(0, 4));
                int month = Integer.parseInt(dateTimeStr.substring(4, 6));
                int day = Integer.parseInt(dateTimeStr.substring(6, 8));
                int hour = dateTimeStr.length() > 8 ? Integer.parseInt(dateTimeStr.substring(8, 10)) : 0;
                int min = dateTimeStr.length() > 10 ? Integer.parseInt(dateTimeStr.substring(10, 12)) : 0;
                int sec = dateTimeStr.length() > 12 ? Integer.parseInt(dateTimeStr.substring(12, 14)) : 0;
                return LocalDateTime.of(year, month, day, hour, min, sec);
            }
        } catch (Exception e) {
            log.warn("Error parsing datetime '{}': {}", dateTimeStr, e.getMessage());
        }
        return null;
    }
}
