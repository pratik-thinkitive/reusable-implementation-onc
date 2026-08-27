package com.onc.C2.service.impl;

import com.onc.C2.dto.PatientMeasureData;
import com.onc.EHR.service.EHRTokenService;
import com.onc.EHR.dto.*;
import com.onc.C2.service.QRDAAggregationService;
import com.onc.C2.service.QRDAExtractionService;
import com.onc.C2.measure.MeasureEvaluator;
import com.onc.C2.service.PatientSummaryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openhealthtools.mdht.uml.cda.*;
import org.openhealthtools.mdht.uml.cda.util.CDAUtil;
import org.openhealthtools.mdht.uml.hl7.datatypes.*;
import org.openhealthtools.mdht.uml.hl7.vocab.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class QRDAAggregationServiceImpl implements QRDAAggregationService {

    @Value("${fhir.base-url}")
    private String baseUrl;

    @Value("${ehr.api.base-url}")
    private String apiBaseUrl;

    /** Separate base: the case API sits under a different path than the rest of the provider API. */
    @Value("${ehr.soap-enrichment.base-url}")
    private String soapEnrichmentBaseUrl;

    // Default doctor_id for appointments (same for every patient as per requirements)
    private static final int DEFAULT_DOCTOR_ID = 34438;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final EHRTokenService ehrTokenService;
    private final QRDAExtractionService extractionService;
    private final PatientSummaryService patientSummaryService;

    @Override
    public ResponseEntity<?> importC2Patients(MultipartFile zipFile) {
        try {
            Map<String, Object> importResult = processPatientZip(zipFile);
            return ResponseEntity.ok(importResult);
        } catch (Exception e) {
            log.error("Error processing C2 ZIP: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Failed to process ZIP", "message", e.getMessage()));
        }
    }

    @Override
    public ResponseEntity<?> generateQrdaIIISummary(List<String> patientIds, String measurementPeriodStart, String measurementPeriodEnd) {
        try {
            if (CollectionUtils.isEmpty(patientIds)) {
                return ResponseEntity.badRequest().body(Map.of("message", "Patient ID list cannot be empty"));
            }

            if (measurementPeriodStart == null || measurementPeriodStart.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Measurement period start date is required (format: yyyy-MM-dd)"));
            }
            if (measurementPeriodEnd == null || measurementPeriodEnd.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Measurement period end date is required (format: yyyy-MM-dd)"));
            }

            LocalDate periodStart;
            LocalDate periodEnd;
            try {
                periodStart = LocalDate.parse(measurementPeriodStart);
                periodEnd = LocalDate.parse(measurementPeriodEnd);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("message", "Invalid date format. Expected format: yyyy-MM-dd (e.g., 2024-01-01)", "error", e.getMessage()));
            }

            if (periodStart.isAfter(periodEnd)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Measurement period start date must be before or equal to end date"));
            }

            log.info("Starting QRDA-III summary generation for {} patient IDs with measurement period: {} to {}", 
                    patientIds.size(), measurementPeriodStart, measurementPeriodEnd);

            // Step 0: Extract doctorId from patient appointments or use default
            Integer doctorId = extractDoctorIdFromPatients(patientIds);
            if (doctorId == null) {
                doctorId = DEFAULT_DOCTOR_ID;
                log.warn("Could not extract doctorId from patients, using default: {}", doctorId);
            } else {
                log.info("Extracted doctorId from patients: {}", doctorId);
            }

            // Fetch doctor details using the doctorId
            DoctorDetailsData doctorDetails = fetchDoctorDetails(doctorId);
            if (doctorDetails == null) {
                log.warn("Could not fetch doctor details for doctorId: {}, using default values", doctorId);
                doctorDetails = createDefaultDoctorDetails();
            } else {
                log.info("Fetched doctor details - Name: {} {}, NPI: {}, TIN: {}, CCN: {}, Taxonomy: {}",
                        doctorDetails.getFirst_name(), doctorDetails.getLast_name(),
                        doctorDetails.getNpi(), doctorDetails.getTax_id_number(),
                        doctorDetails.getCms_certificate_number(), doctorDetails.getTaxonomy_code());
            }

           //  Step 1: Fetch patients from EHR
            List<PatientMeasureData> patients = patientSummaryService.fetchPatients(patientIds).stream()
                    .filter(Objects::nonNull)
                    .filter(p -> p.getPatientId() != null)
                    .collect(Collectors.toList());



            log.info("Fetched {} valid patients out of {} requested patient IDs", patients.size(), patientIds.size());

            if (patients.size() < patientIds.size()) {
                log.warn("Some patient IDs not found or invalid. Generating summary for {} out of {} patients.",
                        patients.size(), patientIds.size());
            }

            if (CollectionUtils.isEmpty(patients)) {
                log.error("No valid patient data found for summary generation");
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "No valid patient data found for summary generation"));
            }

            // Step 2: Calculate measures for each patient
            log.info("Evaluating C2 measures for {} patients with measurement period: {} to {}", 
                    patients.size(), measurementPeriodStart, measurementPeriodEnd);
            patients.forEach(patient -> {
                // Encounters were already extracted by PatientSummaryService from the same
                // appointment data, and extractEncounters replaces rather than appends.
                MeasureEvaluator.evaluateC2Measure(patient, measurementPeriodStart, measurementPeriodEnd);

                // Count assessments and interventions from FormResponse objects
                int assessmentCount = 0;
                int interventionCount = 0;
                if (patient.getFormResponses() != null) {
                    for (FormResponse formResponse : patient.getFormResponses()) {
                        if (formResponse != null) {
                            if (formResponse.getAssessment() != null) {
                                assessmentCount += formResponse.getAssessment().size();
                            }
                            if (formResponse.getIntervention() != null) {
                                interventionCount += formResponse.getIntervention().size();
                            }
                        }
                    }
                }

                log.info("Patient {} Summary - Encounters: {}, Assessments: {}, Interventions: {}, " +
                        "IPOP: {}, DENOM: {}, NUMER: {}",
                        patient.getPatientId(),
                        patient.getEncounters() != null ? patient.getEncounters().size() : 0,
                        assessmentCount,
                        interventionCount,
                        patient.isInInitialPopulation(),
                        patient.isC2Denominator(),
                        patient.isC2Numerator());
            });
            
            // IPOP = patients in initial population (65+ years old at start of measurement period AND qualifying encounter)
            long ipop = patients.stream().filter(PatientMeasureData::isInInitialPopulation).count();
            
            // DENEX = patients in IPOP who are excluded (hospice, palliative, ED-only)
            long denomx = patients.stream()
                    .filter(p -> p.isInInitialPopulation() && p.isDenominatorExcluded())
                    .count();
            
            // DENOM = patients in IPOP who are NOT excluded (DENOM = IPOP - DENEX)
            long denom = patients.stream().filter(PatientMeasureData::isC2Denominator).count();
            
            // NUMER = patients in DENOM who received required fall risk screening
            long numer = patients.stream().filter(PatientMeasureData::isC2Numerator).count();
            
            log.info("=== FINAL MEASURE EVALUATION RESULTS (CMS139) ===");
            log.info("Total Patients          : {}", patients.size());
            log.info("Initial Population (IPOP): {}", ipop);
            log.info("Denominator (DENOM)     : {}", denom);
            log.info("Denominator Exclusion   : {}", denomx);
            log.info("Numerator (NUMER)       : {}", numer);
            log.info("=========================================");

            byte[] zipBytes = generateQrdaIII(patients, doctorDetails, measurementPeriodStart, measurementPeriodEnd);
            log.info("QRDA-III summary generation completed successfully. ZIP size: {} bytes", zipBytes.length);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=qrda-iii-summary.zip")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(zipBytes);

        } catch (Exception e) {
            log.error("Error generating QRDA-III Summary: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Data structure to hold parsed patient data from a single QRDA file
     */
    private static class ParsedPatientData {
        private String fileName;
        private QRDAExtractionService.ExtractedQrdaData parsedData;
        private String patientName;
        private String clinicId;
        
        public ParsedPatientData(String fileName, QRDAExtractionService.ExtractedQrdaData parsedData, String patientName, String clinicId) {
            this.fileName = fileName;
            this.parsedData = parsedData;
            this.patientName = patientName;
            this.clinicId = clinicId;
        }
        
        public String getFileName() { return fileName; }
        public QRDAExtractionService.ExtractedQrdaData getParsedData() { return parsedData; }
        public String getPatientName() { return patientName; }
        public String getClinicId() { return clinicId; }
    }

    private Map<String, Object> processPatientZip(MultipartFile zipFile) throws IOException {
        List<Map<String, Object>> results = new ArrayList<>();

        DoctorDetailsData extractedProvider = null;
        Integer createdDoctorId = null;
        
        // The provider comes from the first document only. Stop after it rather than
        // decompressing every remaining entry into a buffer this pass never reads.
        try (ZipInputStream zipInputStream = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry entry;

            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().endsWith(".xml")) continue;

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                zipInputStream.transferTo(baos);

                // Extract provider details from first QRDA file
                try (InputStream xmlStream = new ByteArrayInputStream(baos.toByteArray())) {
                    log.info("Extracting provider details from first file: {}", entry.getName());
                    QRDAExtractionServiceImpl.ExtractedProviderDetails parsedProvider = extractionService.extractProviderDetails(xmlStream);
                    extractedProvider = parsedProvider.getProviderDetails();
                    log.info("Extracted provider details - NPI: {}, TIN: {}, CCN: {}, Name: {} {}",
                            extractedProvider.getNpi(), extractedProvider.getTax_id_number(),
                            extractedProvider.getCms_certificate_number(),
                            extractedProvider.getFirst_name(), extractedProvider.getLast_name());
                } catch (Exception e) {
                    log.error("Error extracting provider details from first file: {}", e.getMessage(), e);
                }

                if (extractedProvider != null) {
                    createdDoctorId = createProvider(extractedProvider);
                    if (createdDoctorId != null) {
                        log.info("Successfully created provider with doctor ID: {}", createdDoctorId);
                        // Update provider with TIN, NPI, CCN
                        updateProviderDetails(createdDoctorId, extractedProvider);
                    }
                }

                zipInputStream.closeEntry();
                break;
            }
        }

        // Reset zip stream for patient processing
        // Step 1: Parse all QRDA files and collect parsed data
        List<ParsedPatientData> allParsedPatients = new ArrayList<>();
        List<ParsedPatientData> errorFiles = new ArrayList<>();

        try (ZipInputStream zipInputStream = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry entry;

            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().endsWith(".xml")) continue;

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                zipInputStream.transferTo(baos);

                try (InputStream xmlStream = new ByteArrayInputStream(baos.toByteArray())) {
                    log.info("Parsing patient file: {}", entry.getName());

                    QRDAExtractionService.ExtractedQrdaData parsedData = extractionService.extractPatientData(xmlStream);
                    
                    PersonalDetailsData personalDetails = parsedData.getPersonalDetailsData();
                    String patientName = extractPatientName(personalDetails);
                    String clinicId = StringUtils.hasText(parsedData.getClinicId())
                            ? parsedData.getClinicId()
                            : "762"; // fallback clinic ID

                    ParsedPatientData parsedPatient = new ParsedPatientData(
                            entry.getName(), parsedData, patientName, clinicId);
                    allParsedPatients.add(parsedPatient);
                    
                    log.info("Successfully parsed patient: {} from file: {}", patientName, entry.getName());

                } catch (Exception e) {
                    log.error("Error parsing patient file {}: {}", entry.getName(), e.getMessage(), e);
                    // Store error file info for reporting
                    ParsedPatientData errorPatient = new ParsedPatientData(
                            entry.getName(), null, "ERROR", "762");
                    errorFiles.add(errorPatient);
                }

                zipInputStream.closeEntry();
            }
        }

        log.info("Total files parsed: {} ({} successful, {} errors)",
                allParsedPatients.size() + errorFiles.size(), allParsedPatients.size(), errorFiles.size());

        // Step 2: Detect duplicates and merge
        List<ParsedPatientData> mergedPatients = detectAndMergeDuplicates(allParsedPatients);

        // Step 3: Upload merged/unique patients to EHR
        String token = ehrTokenService.getAccessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        
        // Use created doctor ID or default
        int doctorIdToUse = (createdDoctorId != null) ? createdDoctorId : DEFAULT_DOCTOR_ID;

        for (ParsedPatientData mergedPatient : mergedPatients) {
                    Map<String, Object> result = new LinkedHashMap<>();
            result.put("fileName", mergedPatient.getFileName());
            result.put("patientName", mergedPatient.getPatientName());
            result.put("clinicIdUsed", mergedPatient.getClinicId());
            result.put("mergedFrom", mergedPatient.getParsedData() != null ?
                    extractMergedFileNames(mergedPatient) : List.of(mergedPatient.getFileName()));

            try {
                QRDAExtractionService.ExtractedQrdaData parsedData = mergedPatient.getParsedData();
                if (parsedData == null) {
                    result.put("ehrUploadStatus", "FAILED");
                    result.put("message", "No parsed data available");
                    results.add(result);
                    continue;
                }

                PersonalDetailsData personalDetails = parsedData.getPersonalDetailsData();
                List<InsuranceDetails> insuranceDetails = parsedData.getInsuranceDetails();
                AppointmentData appointmentData = parsedData.getAppointmentData();
                FormResponse formResponse = parsedData.getFormResponse();

                // Upload patient to EHR
                String patientId = uploadPatient(personalDetails, mergedPatient.getClinicId(), headers, result);
                    
                    if (patientId != null) {
                        uploadInsurance(insuranceDetails, patientId, headers, result);
                        
                    uploadPersonalDetailsForm(personalDetails, patientId, mergedPatient.getClinicId(), headers, result);

                    // Create patient case
                    String caseId = createPatientCase(patientId, mergedPatient.getClinicId(), insuranceDetails, headers, result);
                        
                    // Upload appointments/encounters
                    String firstAppointmentId = uploadAppointments(appointmentData, patientId, mergedPatient.getClinicId(), caseId, doctorIdToUse, headers, result);
                        
                        String soapContextId = null;
                        if (firstAppointmentId != null) {
                            Map<String, String> soapContextInfo = createInitialSoapContext(patientId, firstAppointmentId, caseId, headers, result);
                            soapContextId = soapContextInfo.get("contextId");

                            if (soapContextId != null) {
                                result.put("soapContextId", soapContextId);
                                log.info("Step 5.5: Created SOAP context with contextId: {} for patient ID: {}", soapContextId, patientId);

                            // Upload SOAP Assessment & Intervention
                                if (formResponse != null) {
                                    uploadSoapDetails(patientId, soapContextId, formResponse, headers, result);
                                } else {
                                    log.warn("Step 6 WARNING: No FormResponse found in QRDA file for patient ID: {}", patientId);
                                }

                            } else {
                                log.warn("Step 5.5 WARNING: SOAP context created but no context_id found for patient ID: {}", patientId);
                            }

                        } else {
                            log.warn("Step 5.5 WARNING: No appointment ID available to create SOAP context for patient ID: {}", patientId);
                        }
                        
                        result.put("ehrUploadStatus", "SUCCESS");
                        result.put("message", "Uploaded successfully");
                    } else {
                        result.put("ehrUploadStatus", "FAILED");
                        result.put("message", "Failed to create patient - subsequent steps skipped");
                    }

                    results.add(result);
                log.info("Completed processing merged patient: {} - Status: {}", 
                        mergedPatient.getPatientName(), result.get("ehrUploadStatus"));

                } catch (Exception e) {
                result.put("ehrUploadStatus", "ERROR");
                result.put("error", e.getMessage());
                results.add(result);
                log.error("Error uploading merged patient {}: {}", mergedPatient.getPatientName(), e.getMessage(), e);
            }
        }

        for (ParsedPatientData errorFile : errorFiles) {
                    Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("fileName", errorFile.getFileName());
            errorResult.put("ehrUploadStatus", "FAILED");
            errorResult.put("message", "Failed to parse QRDA XML file");
                    results.add(errorResult);
        }

        // Return response with doctorId and results
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("doctorId", createdDoctorId);
        response.put("results", results);
        if (createdDoctorId != null) {
            response.put("message", "Provider created successfully with doctor ID: " + createdDoctorId);
        } else {
            response.put("message", "Provider creation failed or skipped");
        }
        
        return response;
    }

    //Detect duplicate patients and merge their data
    private List<ParsedPatientData> detectAndMergeDuplicates(List<ParsedPatientData> allPatients) {
        if (allPatients == null || allPatients.isEmpty()) {
            return new ArrayList<>();
        }

        log.info("Starting duplicate detection for {} patients", allPatients.size());
        
        // Group patients by similarity
        Map<String, List<ParsedPatientData>> patientGroups = new LinkedHashMap<>();
        List<ParsedPatientData> processedPatients = new ArrayList<>();
        
        for (ParsedPatientData patient : allPatients) {
            if (patient.getParsedData() == null || patient.getParsedData().getPersonalDetailsData() == null) {
                // Skip invalid patients
                processedPatients.add(patient);
                continue;
            }
            
            String patientKey = generatePatientKey(patient);
            
            // Check if this patient matches any existing group
            boolean foundMatch = false;
            for (Map.Entry<String, List<ParsedPatientData>> entry : patientGroups.entrySet()) {
                ParsedPatientData existingPatient = entry.getValue().get(0);
                if (areDuplicates(patient, existingPatient)) {
                    entry.getValue().add(patient);
                    foundMatch = true;
                    log.info("Found duplicate: {} matches existing patient group (key: {})", 
                            patient.getFileName(), entry.getKey());
                    break;
                }
            }
            
            if (!foundMatch) {
                // Create new group
                List<ParsedPatientData> newGroup = new ArrayList<>();
                newGroup.add(patient);
                patientGroups.put(patientKey, newGroup);
            }
        }
        
        // Merge patients in each group
        List<ParsedPatientData> mergedPatients = new ArrayList<>();
        for (Map.Entry<String, List<ParsedPatientData>> entry : patientGroups.entrySet()) {
            List<ParsedPatientData> group = entry.getValue();
            if (group.size() == 1) {
                mergedPatients.add(group.get(0));
                log.info("No duplicates found for patient: {} (file: {})", 
                        group.get(0).getPatientName(), group.get(0).getFileName());
            } else {
                // Merge duplicates
                ParsedPatientData merged = mergePatients(group);
                mergedPatients.add(merged);
                log.info("Merged {} duplicate patients into one. Files: {}", 
                        group.size(), group.stream().map(ParsedPatientData::getFileName).collect(Collectors.toList()));
            }
        }
        
        log.info("Duplicate detection complete: {} original patients -> {} unique patients", 
                allPatients.size(), mergedPatients.size());
        
        return mergedPatients;
    }

     // Generate a key for patient grouping based on demographics
    private String generatePatientKey(ParsedPatientData patient) {
        PersonalDetailsData personalDetails = patient.getParsedData().getPersonalDetailsData();
        if (personalDetails == null || personalDetails.getResponse() == null ||
            personalDetails.getResponse().getPatientInformation() == null) {
            return "UNKNOWN_" + patient.getFileName();
        }
        
        PatientInformation patientInfo = extractPatientInformation(personalDetails);
        if (patientInfo == null) {
            return "UNKNOWN_" + patient.getFileName();
        }
        
        // Use normalized name and DOB as key
        String firstName = normalizeName(patientInfo.getFirstName());
        String lastName = normalizeName(patientInfo.getLastName());
        String dob = patientInfo.getBirthDate();
        
        return String.format("%s_%s_%s", 
                firstName != null ? firstName : "NULL",
                lastName != null ? lastName : "NULL",
                dob != null ? dob : "NULL");
    }

     // Check if two patients are duplicates
    private boolean areDuplicates(ParsedPatientData patient1, ParsedPatientData patient2) {
        PersonalDetailsData pd1 = patient1.getParsedData().getPersonalDetailsData();
        PersonalDetailsData pd2 = patient2.getParsedData().getPersonalDetailsData();
        
        if (pd1 == null || pd2 == null) {
            return false;
        }
        
        PatientInformation info1 = extractPatientInformation(pd1);
        PatientInformation info2 = extractPatientInformation(pd2);
        
        if (info1 == null || info2 == null) {
            return false;
        }
        
        // Condition 1: Same clinical data but different demographics
        boolean sameClinicalData = hasSameClinicalData(patient1, patient2);
        boolean similarDemographics = areSimilarDemographics(info1, info2);
        
        if (sameClinicalData && similarDemographics) {
            log.debug("Duplicate detected (Condition 1): Same clinical data, similar demographics");
            return true;
        }
        
        // Condition 2: Same demographics but split clinical data
        boolean sameDemographics = areSameDemographics(info1, info2);
        boolean differentClinicalData = !hasSameClinicalData(patient1, patient2);
        
        if (sameDemographics && differentClinicalData) {
            log.debug("Duplicate detected (Condition 2): Same demographics, different clinical data (split data)");
            return true;
        }
        
        return false;
    }

    private boolean areSimilarDemographics(PatientInformation info1, PatientInformation info2) {
        String firstName1 = normalizeName(info1.getFirstName());
        String lastName1 = normalizeName(info1.getLastName());
        String firstName2 = normalizeName(info2.getFirstName());
        String lastName2 = normalizeName(info2.getLastName());
        
        boolean namesSimilar = (firstName1 != null && firstName2 != null && 
                               (firstName1.equals(firstName2) || 
                                areNamesSimilar(firstName1, firstName2))) &&
                               (lastName1 != null && lastName2 != null && 
                                (lastName1.equals(lastName2) || 
                                 areNamesSimilar(lastName1, lastName2)));
        
        // Compare DOB (allow for slight differences or missing)
        boolean dobSimilar = areDOBSimilar(info1.getBirthDate(), info2.getBirthDate());
        
        // If names are similar and DOB is similar, consider them similar demographics
        return namesSimilar && dobSimilar;
    }

    private boolean areSameDemographics(PatientInformation info1, PatientInformation info2) {
        String firstName1 = normalizeName(info1.getFirstName());
        String lastName1 = normalizeName(info1.getLastName());
        String firstName2 = normalizeName(info2.getFirstName());
        String lastName2 = normalizeName(info2.getLastName());
        
        boolean namesMatch = Objects.equals(firstName1, firstName2) && 
                            Objects.equals(lastName1, lastName2);
        
        boolean dobMatch = Objects.equals(info1.getBirthDate(), info2.getBirthDate());
        
        return namesMatch && dobMatch;
    }

    private boolean hasSameClinicalData(ParsedPatientData patient1, ParsedPatientData patient2) {
        // Compare appointments/encounters
        AppointmentData apt1 = patient1.getParsedData().getAppointmentData();
        AppointmentData apt2 = patient2.getParsedData().getAppointmentData();
        
        // Compare FormResponse (assessments/interventions)
        FormResponse form1 = patient1.getParsedData().getFormResponse();
        FormResponse form2 = patient2.getParsedData().getFormResponse();
        
        // Simple comparison: if both have same number of appointments and same form responses, consider same clinical data
        boolean appointmentsSimilar = (apt1 == null && apt2 == null) ||
                                     (apt1 != null && apt2 != null && 
                                      apt1.getAppointments() != null && apt2.getAppointments() != null &&
                                      apt1.getAppointments().size() == apt2.getAppointments().size());
        
        boolean formsSimilar = (form1 == null && form2 == null) ||
                              (form1 != null && form2 != null &&
                               areFormResponsesSimilar(form1, form2));
        
        return appointmentsSimilar && formsSimilar;
    }

    private boolean areFormResponsesSimilar(FormResponse form1, FormResponse form2) {
        if (form1 == null && form2 == null) return true;
        if (form1 == null || form2 == null) return false;
        
        boolean assessmentsSimilar = (form1.getAssessment() == null && form2.getAssessment() == null) ||
                                    (form1.getAssessment() != null && form2.getAssessment() != null &&
                                     form1.getAssessment().size() == form2.getAssessment().size());
        
        boolean interventionsSimilar = (form1.getIntervention() == null && form2.getIntervention() == null) ||
                                      (form1.getIntervention() != null && form2.getIntervention() != null &&
                                       form1.getIntervention().size() == form2.getIntervention().size());
        
        return assessmentsSimilar && interventionsSimilar;
    }

    private String normalizeName(String name) {
        if (name == null) return null;
        return name.trim().toUpperCase().replaceAll("\\s+", " ");
    }


    private boolean areNamesSimilar(String name1, String name2) {
        if (name1 == null || name2 == null) return false;
        
        name1 = normalizeName(name1);
        name2 = normalizeName(name2);
        
        // Exact match
        if (name1.equals(name2)) return true;
        
        // One is initial of the other (e.g., "JOHN" vs "J")
        if (name1.length() == 1 && name2.startsWith(name1)) return true;
        if (name2.length() == 1 && name1.startsWith(name2)) return true;
        
        // One contains the other (handles nicknames like "BOB" vs "ROBERT")
        if (name1.contains(name2) || name2.contains(name1)) return true;
        
        // Levenshtein distance check for misspellings (simple version)
        int maxLength = Math.max(name1.length(), name2.length());
        if (maxLength == 0) return true;

        // If distance is small relative to length, consider similar
        int threshold = Math.max(2, maxLength / 4);

        // Edit distance is never smaller than the length difference, so a pair this far apart
        // cannot pass the threshold - the same answer, without building the matrix.
        if (Math.abs(name1.length() - name2.length()) > threshold) {
            return false;
        }

        return levenshteinDistance(name1, name2) <= threshold;
    }

    /**
     * Two rolling rows rather than the full matrix. Each cell only ever reads the row above and
     * the cell to its left, so the result is identical at O(min(m,n)) memory instead of O(m*n).
     */
    private int levenshteinDistance(String s1, String s2) {
        int[] previous = new int[s2.length() + 1];
        int[] current = new int[s2.length() + 1];

        for (int j = 0; j <= s2.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= s1.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= s2.length(); j++) {
                current[j] = Math.min(
                        Math.min(previous[j] + 1, current[j - 1] + 1),
                        previous[j - 1] + (s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1)
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[s2.length()];
    }

    private boolean areDOBSimilar(String dob1, String dob2) {
        if (dob1 == null && dob2 == null) return true;
        if (dob1 == null || dob2 == null) return false; // One missing is not similar
        
        // Normalize DOB formats
        dob1 = dob1.trim().replaceAll("-", "");
        dob2 = dob2.trim().replaceAll("-", "");
        
        // Exact match
        if (dob1.equals(dob2)) return true;
        
        // Check if dates are close (within a few days) - simplified check
        try {
            LocalDate date1 = parseDOB(dob1);
            LocalDate date2 = parseDOB(dob2);
            if (date1 != null && date2 != null) {
                long daysDiff = Math.abs(ChronoUnit.DAYS.between(date1, date2));
                return daysDiff <= 7; // Allow 7 days difference
            }
        } catch (Exception e) {
            return dob1.equals(dob2);
        }
        
        return false;
    }

    private LocalDate parseDOB(String dob) {
        if (dob == null || dob.isEmpty()) return null;
        
        try {
            // Try yyyy-MM-dd format
            if (dob.contains("-")) {
                return LocalDate.parse(dob.substring(0, 10));
            }
            // Try yyyyMMdd format
            if (dob.length() >= 8) {
                return LocalDate.parse(dob.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
            }
        } catch (Exception e) {
            log.debug("Failed to parse DOB: {}", dob);
        }
        
        return null;
    }

    private ParsedPatientData mergePatients(List<ParsedPatientData> patients) {
        if (patients == null || patients.isEmpty()) {
            return null;
        }
        
        if (patients.size() == 1) {
            return patients.get(0);
        }
        
        // Use the LAST patient (second patient) as base - this patient has priority
        ParsedPatientData merged = patients.get(patients.size() - 1); // Last patient = second patient
        QRDAExtractionService.ExtractedQrdaData mergedData = merged.getParsedData();
        
        log.info("Merging {} patients - using patient '{}' (from file: {}) as base with priority",
                patients.size(), merged.getPatientName(), merged.getFileName());
        
        // Merge data from earlier patients (first, third, etc.) into the second patient
        // CRITICAL: All clinical data must be merged additively - never skip any data
        for (int i = 0; i < patients.size() - 1; i++) {
            ParsedPatientData patient = patients.get(i);
            QRDAExtractionService.ExtractedQrdaData data = patient.getParsedData();
            
            log.info("Merging clinical data from patient file {}: {}", i + 1, patient.getFileName());
            
            // Merge appointments (combine lists, remove duplicates)
            if (data.getAppointmentData() != null && data.getAppointmentData().getAppointments() != null) {
                if (mergedData.getAppointmentData() == null) {
                    mergedData.setAppointmentData(new AppointmentData());
                    mergedData.getAppointmentData().setAppointments(new ArrayList<>());
                }
                if (mergedData.getAppointmentData().getAppointments() == null) {
                    mergedData.getAppointmentData().setAppointments(new ArrayList<>());
                }
                
                // Add appointments from this patient (avoid duplicates by appointment_id)
                Set<Integer> existingAppointmentIds = mergedData.getAppointmentData().getAppointments().stream()
                        .filter(Objects::nonNull)
                        .map(Appointment::getAppointment_id)
                        .filter(id -> id != 0) // Filter out invalid IDs (0 is default for primitive int)
                        .collect(Collectors.toSet());
                
                for (Appointment apt : data.getAppointmentData().getAppointments()) {
                    if (apt != null && apt.getAppointment_id() != 0 && 
                        !existingAppointmentIds.contains(apt.getAppointment_id())) {
                        mergedData.getAppointmentData().getAppointments().add(apt);
                        existingAppointmentIds.add(apt.getAppointment_id());
                    }
                }
            }
            
            // Merge insurance (combine lists, remove duplicates)
            if (data.getInsuranceDetails() != null && !data.getInsuranceDetails().isEmpty()) {
                if (mergedData.getInsuranceDetails() == null) {
                    mergedData.setInsuranceDetails(new ArrayList<>());
                }
                
                // Add insurance from this patient (avoid duplicates)
                Set<String> existingInsuranceIds = mergedData.getInsuranceDetails().stream()
                        .filter(Objects::nonNull)
                        .map(InsuranceDetails::getInsurance_card_id)
                        .filter(Objects::nonNull)
                        .map(String::valueOf)
                        .collect(Collectors.toSet());
                
                for (InsuranceDetails ins : data.getInsuranceDetails()) {
                    if (ins != null && ins.getInsurance_card_id() != null &&
                        !existingInsuranceIds.contains(String.valueOf(ins.getInsurance_card_id()))) {
                        mergedData.getInsuranceDetails().add(ins);
                        existingInsuranceIds.add(String.valueOf(ins.getInsurance_card_id()));
                    }
                }
            }
            
            // Merge FormResponse (combine assessments and interventions)
            // CRITICAL: All clinical data must be merged additively - never skip any codes
            // This ensures hospice codes and other exclusion codes from any file are preserved
            if (data.getFormResponse() != null) {
                if (mergedData.getFormResponse() == null) {
                    mergedData.setFormResponse(new FormResponse());
                }
                
                FormResponse mergedForm = mergedData.getFormResponse();
                FormResponse formToMerge = data.getFormResponse();
                
                // Merge assessments - ADD ALL assessments from this patient
                // Use unique keys to ensure no data is lost (even if keys are the same)
                if (formToMerge.getAssessment() != null && !formToMerge.getAssessment().isEmpty()) {
                    if (mergedForm.getAssessment() == null) {
                        mergedForm.setAssessment(new LinkedHashMap<>());
                    }
                    
                    log.info("Merging {} assessment(s) from patient file: {}", 
                            formToMerge.getAssessment().size(), patient.getFileName());
                    
                    // Add ALL assessments with unique keys to ensure no data is skipped
                    for (Map.Entry<String, CodeSection> entry : formToMerge.getAssessment().entrySet()) {
                        String key = entry.getKey();
                        CodeSection codeSection = entry.getValue();
                        
                        if (codeSection == null) {
                            log.warn("Skipping null CodeSection for assessment key: {}", key);
                            continue;
                        }
                        
                        // Generate unique key to ensure all assessments are preserved
                        int counter = 1;
                        String uniqueKey = key;
                        while (mergedForm.getAssessment().containsKey(uniqueKey)) {
                            uniqueKey = key + "_merged_" + counter++;
                        }
                        
                        mergedForm.getAssessment().put(uniqueKey, codeSection);
                        log.debug("Added assessment '{}' (renamed to '{}') from file: {}", 
                                key, uniqueKey, patient.getFileName());
                    }
                }
                
                // Merge interventions - ADD ALL interventions from this patient
                // Use unique keys to ensure no data is lost (even if keys are the same)
                if (formToMerge.getIntervention() != null && !formToMerge.getIntervention().isEmpty()) {
                    if (mergedForm.getIntervention() == null) {
                        mergedForm.setIntervention(new LinkedHashMap<>());
                    }
                    
                    log.info("Merging {} intervention(s) from patient file: {}", 
                            formToMerge.getIntervention().size(), patient.getFileName());
                    
                    // Add ALL interventions with unique keys to ensure no data is skipped
                    for (Map.Entry<String, CodeSection> entry : formToMerge.getIntervention().entrySet()) {
                        String key = entry.getKey();
                        CodeSection codeSection = entry.getValue();
                        
                        if (codeSection == null) {
                            log.warn("Skipping null CodeSection for intervention key: {}", key);
                            continue;
                        }
                        
                        // Generate unique key to ensure all interventions are preserved
                        int counter = 1;
                        String uniqueKey = key;
                        while (mergedForm.getIntervention().containsKey(uniqueKey)) {
                            uniqueKey = key + "_merged_" + counter++;
                        }
                        
                        mergedForm.getIntervention().put(uniqueKey, codeSection);
                        log.debug("Added intervention '{}' (renamed to '{}') from file: {}", 
                                key, uniqueKey, patient.getFileName());
                    }
                }
                
                log.info("After merging FormResponse from file '{}': {} assessment(s), {} intervention(s) in merged patient",
                        patient.getFileName(),
                        mergedForm.getAssessment() != null ? mergedForm.getAssessment().size() : 0,
                        mergedForm.getIntervention() != null ? mergedForm.getIntervention().size() : 0);
            }
            
            // Do NOT merge personal details from earlier patients - second patient has priority
            // Only merge clinical data (appointments, insurance, form responses) which is done above
        }
        
        // ALWAYS use second patient's name (last in list = second patient when 2 duplicates found)
        String mergedFileName = patients.stream()
                .map(ParsedPatientData::getFileName)
                .collect(Collectors.joining(", "));
        
        // CRITICAL: Always use second patient's name for merged patient
        // Second patient = last patient in the list (patients.get(patients.size() - 1))
        String mergedPatientName = merged.getPatientName(); // Second patient's name (from file name or extracted)
        
        // Fallback: If name is not available, extract from second patient's personal details
        if (mergedPatientName == null || mergedPatientName.isEmpty() || "Unknown Patient".equals(mergedPatientName)) {
            mergedPatientName = extractPatientName(mergedData.getPersonalDetailsData());
            log.debug("Extracted patient name '{}' from second patient's personal details", mergedPatientName);
        }
        
        log.info("Merged patient will use name: '{}' from second patient (file: {}). " +
                "Merged clinical data from {} file(s): {}", 
                mergedPatientName, merged.getFileName(), patients.size(), mergedFileName);

        return new ParsedPatientData(mergedFileName, mergedData, mergedPatientName, merged.getClinicId());
    }

    private List<String> extractMergedFileNames(ParsedPatientData patient) {
        if (patient.getFileName().contains(", ")) {
            return Arrays.asList(patient.getFileName().split(", "));
        }
        return List.of(patient.getFileName());
    }

    private String extractPatientName(PersonalDetailsData personalDetails) {
        if (personalDetails != null && personalDetails.getResponse() != null 
                && personalDetails.getResponse().getPatientInformation() != null) {
            Map<String, PatientInformation> patientInfoMap = personalDetails.getResponse().getPatientInformation();
            PatientInformation patientInfo = patientInfoMap.values().stream()
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
            
            if (patientInfo != null) {
                String firstName = StringUtils.hasText(patientInfo.getFirstName()) ? patientInfo.getFirstName() : "";
                String lastName = StringUtils.hasText(patientInfo.getLastName()) ? patientInfo.getLastName() : "";
                return (firstName + " " + lastName).trim();
            }
        }
        return "Unknown Patient";
    }

    private String uploadPatient(PersonalDetailsData personalDetails, String clinicId, 
                                 HttpHeaders headers, Map<String, Object> result) {
        String patientApiUrl = apiBaseUrl + "/patient";
        
        try {
            PatientInformation patientInfo = extractPatientInformation(personalDetails);
            if (patientInfo == null) {
                result.put("createPatientError", "No patient information found");
                return null;
            }
            
            log.info("Step 1: Creating patient: {} {}", patientInfo.getFirstName(), patientInfo.getLastName());
            
            Map<String, Object> patientRequest = buildPatientRequest(patientInfo, clinicId);
            
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(patientRequest, headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    patientApiUrl,
                    HttpMethod.POST,
                    requestEntity,
                    Map.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                String patientId = extractPatientIdFromResponse(responseBody);
                
                result.put("createPatientStatus", response.getStatusCode().value());
                result.put("patientId", patientId);
                
                log.info("Step 1 SUCCESS: Patient created with ID: {}", patientId);
                return patientId;
            } else {
                result.put("createPatientStatus", response.getStatusCode().value());
                result.put("createPatientError", "Failed to create patient: " + response.getStatusCode());
                log.error("Step 1 FAILED: Patient creation failed - Status: {}", response.getStatusCode());
                return null;
            }
        } catch (Exception e) {
            result.put("createPatientStatus", HttpStatus.INTERNAL_SERVER_ERROR.value());
            result.put("createPatientError", e.getMessage());
            log.error("Step 1 ERROR: Exception creating patient - {}", e.getMessage(), e);
            return null;
        }
    }

    private void uploadInsurance(List<InsuranceDetails> insuranceDetails, String patientId, 
                                 HttpHeaders headers, Map<String, Object> result) {
        String insuranceApiUrl = apiBaseUrl + "/insurance/cards";
        
        if (CollectionUtils.isEmpty(insuranceDetails)) {
            log.info("Step 2 SKIPPED: No insurance data found for patient ID: {}", patientId);
            result.put("insuranceUploadStatus", "SKIPPED - No insurance data");
            return;
        }

        try {
            log.info("Step 2: Uploading {} insurance record(s) for patient ID: {}", 
                    insuranceDetails.size(), patientId);
            
            int successCount = 0;
            int failCount = 0;
            
            for (InsuranceDetails insurance : insuranceDetails) {
                try {
                    Map<String, Object> insuranceRequest = buildInsuranceRequest(insurance, patientId);
                    
                    HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(insuranceRequest, headers);
                    ResponseEntity<Map> response = restTemplate.exchange(
                            insuranceApiUrl,
                            HttpMethod.POST,
                            requestEntity,
                            Map.class
                    );

                    if (response.getStatusCode().is2xxSuccessful()) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                } catch (Exception e) {
                    failCount++;
                    log.error("Error uploading insurance for patient ID: {} - {}", patientId, e.getMessage());
                }
            }
            
            result.put("insuranceUploadStatus", HttpStatus.OK.value());
            result.put("insuranceCount", insuranceDetails.size());
            result.put("insuranceSuccessCount", successCount);
            result.put("insuranceFailCount", failCount);
            log.info("Step 2 COMPLETED: Processed {} insurance records ({} success, {} failed)", 
                    insuranceDetails.size(), successCount, failCount);
        } catch (Exception e) {
            result.put("insuranceUploadStatus", HttpStatus.INTERNAL_SERVER_ERROR.value());
            result.put("insuranceUploadError", e.getMessage());
            log.error("Step 2 ERROR: Exception processing insurance - {}", e.getMessage(), e);
        }
    }

    private void uploadPersonalDetailsForm(PersonalDetailsData personalDetails, String patientId, 
                                          String clinicId, HttpHeaders headers, Map<String, Object> result) {
        // Both ids come out of the same payload, so read it once rather than calling twice.
        Map<String, String> personalDetailsInfo = getPersonalDetailsInfo(patientId);
        String submissionId = personalDetailsInfo != null ? personalDetailsInfo.get("submissionId") : null;
        String digestDefinitionId = personalDetailsInfo != null ? personalDetailsInfo.get("digestDefinitionId") : null;
        String personalDetailsApiUrl = apiBaseUrl + "/personal-details/" + submissionId ;
        
        try {
            log.info("Step 3: Uploading personal details form for patient ID: {}", patientId);
            
            Map<String, Object> personalDetailsRequest = buildPersonalDetailsRequest(personalDetails, patientId, clinicId, digestDefinitionId);
            
            // Log the request to verify race/ethnicity are included
            if (personalDetailsRequest.containsKey("response")) {
                Map<String, Object> responseMap = (Map<String, Object>) personalDetailsRequest.get("response");
                if (responseMap.containsKey("Patient information")) {
                    Map<String, Object> patientInfoMap = (Map<String, Object>) responseMap.get("Patient information");
                    for (Map.Entry<String, Object> entry : patientInfoMap.entrySet()) {
                        if (entry.getValue() instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> patientInfo = (Map<String, Object>) entry.getValue();
                            log.debug("Step 3: Patient info for key {} - race: {}, ethnicity: {}", 
                                    entry.getKey(), 
                                    patientInfo.get("race_with_more_granular_race_code"),
                                    patientInfo.get("ethnicity"));
                        }
                    }
                }
            }
            
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(personalDetailsRequest, headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    personalDetailsApiUrl,
                    HttpMethod.POST,
                    requestEntity,
                    Map.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                result.put("personalDetailsUploadStatus", response.getStatusCode().value());
                log.info("Step 3 SUCCESS: Personal details form uploaded for patient ID: {}", patientId);
            } else {
                result.put("personalDetailsUploadStatus", response.getStatusCode().value());
                result.put("personalDetailsUploadError", "Failed to upload personal details: " + response.getStatusCode());
                log.warn("Step 3 WARNING: Personal details upload failed for patient ID: {} - Status: {}", 
                        patientId, response.getStatusCode());
                if (response.getBody() != null) {
                    log.warn("Step 3 WARNING: Response body: {}", response.getBody());
                }
            }
        } catch (Exception e) {
            result.put("personalDetailsUploadStatus", HttpStatus.INTERNAL_SERVER_ERROR.value());
            result.put("personalDetailsUploadError", e.getMessage());
            log.error("Step 3 ERROR: Exception uploading personal details for patient ID: {} - {}", 
                    patientId, e.getMessage(), e);
        }
    }

    private String createPatientCase(String patientId, String clinicId, List<InsuranceDetails> insuranceDetails,
                                     HttpHeaders headers, Map<String, Object> result) {
        try {
            log.info("Step 4.5: Creating patient case for patient ID: {}", patientId);
            
            Map<String, Object> caseRequest = new LinkedHashMap<>();
            
            try {
                caseRequest.put("patient_id", Integer.parseInt(patientId));
            } catch (NumberFormatException e) {
                caseRequest.put("patient_id", patientId);
            }
            
            caseRequest.put("case_title", "Case");

            caseRequest.put("specialization", "MSK");

            caseRequest.put("referring_physician_details", null);
            caseRequest.put("secondary_referring_physician_details", null);
            caseRequest.put("tertiary_referring_physician_details", null);
            caseRequest.put("nurse_manager_details", null);
            caseRequest.put("body_pain_notes", "");
            caseRequest.put("other_relevant_date", "");
            caseRequest.put("other_date_qual_code", "");
            
            caseRequest.put("insurance_card_list", new ArrayList<>());

            caseRequest.put("icd_codes", new ArrayList<>());
            caseRequest.put("date_of_injury", "");
            caseRequest.put("authorization_required", false);
            caseRequest.put("is_auth_required_manually_changed", false);
            caseRequest.put("discount", null);
            
            Map<String, Object> affectedComponents = new LinkedHashMap<>();
            List<String> bodyParts = new ArrayList<>();
            bodyParts.add("ELBOW"); // Default body part matching curl example
            affectedComponents.put("body_parts", bodyParts);
            caseRequest.put("affected_components", affectedComponents);

            caseRequest.put("primary_selector_type", "bodyparts");
            

            String caseApiUrl = soapEnrichmentBaseUrl + "/patientCase";
            
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(caseRequest, headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    caseApiUrl,
                    HttpMethod.POST,
                    requestEntity,
                    Map.class
            );
            
            log.info("Patient case API response: {}", response.getBody());
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                
                // Check for error in response body
                Object codeObj = responseBody.get("code");
                if (codeObj != null) {
                    int code = 0;
                    try {
                        if (codeObj instanceof Integer) {
                            code = (Integer) codeObj;
                        } else {
                            code = Integer.parseInt(String.valueOf(codeObj));
                        }
                        
                        if (code != 0 && code != 200 && code != 2000 && code != 20000) {
                            String message = responseBody.get("message") != null 
                                    ? String.valueOf(responseBody.get("message")) 
                                    : "Unknown error";
                            log.warn("Step 4.5 WARNING: Case creation returned error code: {}, message: {}", code, message);
                        } else {
                            log.info("Step 4.5: Case creation returned success code: {}", code);
                        }
                    } catch (NumberFormatException e) {
                        log.warn("Step 4.5 WARNING: Could not parse response code: {}", codeObj);
                    }
                }
                
                String caseId = null;
                if (responseBody.containsKey("data")) {
                    Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
                    if (data != null) {
                       if (data.containsKey("emmacare_case_id"))
                            caseId = String.valueOf(data.get("emmacare_case_id"));
                    }
                } else if (responseBody.containsKey("case_id")) {
                    caseId = String.valueOf(responseBody.get("case_id"));
                } else if (responseBody.containsKey("emmacare_case_id")) {
                    caseId = String.valueOf(responseBody.get("emmacare_case_id"));
                }
                
                if (caseId != null && !caseId.isEmpty() && !"null".equals(caseId)) {
                    log.info("Step 4.5 SUCCESS: Patient case created with ID: {}", caseId);
                    result.put("caseCreationStatus", "SUCCESS");
                    result.put("caseId", caseId);
                    return caseId;
                } else {
                    log.warn("Step 4.5 WARNING: Case creation response did not contain case_id. Response: {}", responseBody);
                    String fallbackCaseId = "EMMACARE_CASE_" + UUID.randomUUID().toString().replace("-", "");
                    result.put("caseCreationStatus", "WARNING - Generated fallback case ID");
                    result.put("caseId", fallbackCaseId);
                    return fallbackCaseId;
                }
            } else {
                log.warn("Step 4.5 WARNING: Case creation failed - HTTP Status: {}. Generating fallback case ID.", 
                        response.getStatusCode());
                // Generate a case ID as fallback
                String fallbackCaseId = "EMMACARE_CASE_" + UUID.randomUUID().toString().replace("-", "");
                result.put("caseCreationStatus", "WARNING - Generated fallback case ID");
                result.put("caseId", fallbackCaseId);
                return fallbackCaseId;
            }
        } catch (Exception e) {
            log.error("Step 4.5 ERROR: Exception creating patient case for patient ID: {} - {}. Generating fallback case ID.", 
                    patientId, e.getMessage(), e);
            // Generate a case ID as fallback
            String fallbackCaseId = "EMMACARE_CASE_" + UUID.randomUUID().toString().replace("-", "");
            result.put("caseCreationStatus", "ERROR - Generated fallback case ID");
            result.put("caseId", fallbackCaseId);
            return fallbackCaseId;
        }
    }

    private String uploadAppointments(AppointmentData appointmentData, String patientId, String clinicId, String caseId,
                                   int doctorId, HttpHeaders headers, Map<String, Object> result) {
        log.info("=== UPLOAD APPOINTMENTS - START ===");

        if (appointmentData == null || CollectionUtils.isEmpty(appointmentData.getAppointments())) {
            return null;
        }

        
        for (int i = 0; i < appointmentData.getAppointments().size(); i++) {
            Appointment apt = appointmentData.getAppointments().get(i);
            log.info("Appointment {} to upload: appointment_id={}, type={}, date_time={}, end_date_time={}, status={}, category={}", 
                    i + 1,
                    apt.getAppointment_id(),
                    apt.getType(),
                    apt.getDate_time(),
                    apt.getEnd_date_time(),
                    apt.getAppointment_status(),
                    apt.getCategory() != null ? apt.getCategory().stream()
                            .map(c -> c != null ? c.getName() : "null").collect(Collectors.joining(", ")) : "null");
        }

        String appointmentApiUrl = apiBaseUrl + "/appointment";
        
        try {
            int successCount = 0;
            int failCount = 0;
            String firstSuccessfulAppointmentId = null;
            
            for (int i = 0; i < appointmentData.getAppointments().size(); i++) {
                Appointment appointment = appointmentData.getAppointments().get(i);
                try {
                    log.info("--- Building appointment request {} of {} ---", i + 1, appointmentData.getAppointments().size());
                    
                    Map<String, Object> appointmentRequest = buildAppointmentRequest(appointment, patientId, clinicId, caseId, doctorId);
                    

                    HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(appointmentRequest, headers);
                    log.info("Sending POST request to: {}", appointmentApiUrl);
                    
                    ResponseEntity<Map> response = restTemplate.exchange(
                            appointmentApiUrl,
                            HttpMethod.POST,
                            requestEntity,
                            Map.class
                    );
                    
                    Map<String, Object> responseBody = response.getBody();
                    log.info("Appointment {} upload response - HTTP Status: {}, Response Body: {}", 
                            i + 1, response.getStatusCode(), responseBody);

                    boolean isSuccess = false;
                    if (response.getStatusCode().is2xxSuccessful() && responseBody != null) {
                        Object codeObj = responseBody.get("code");
                        if (codeObj != null) {
                            int code = 0;
                            try {
                                if (codeObj instanceof Integer) {
                                    code = (Integer) codeObj;
                                } else if (codeObj instanceof String) {
                                    code = Integer.parseInt((String) codeObj);
                                } else {
                                    code = Integer.parseInt(String.valueOf(codeObj));
                                }

                                if (code == 0 || code == 200 || code == 2000 || code == 20000) {
                                    isSuccess = true;
                                    log.info("Step 4 SUCCESS: Appointment upload succeeded for patient ID: {} - Response code: {}", 
                                            patientId, code);
                                } else {
                                    String message = responseBody.get("message") != null
                                            ? String.valueOf(responseBody.get("message")) 
                                            : "Unknown error";
                                    log.warn("Step 4 ERROR: Appointment upload failed for patient ID: {} - Error code: {}, Message: {}", 
                                            patientId, code, message);
                                }
                            } catch (NumberFormatException e) {
                                isSuccess = true;
                            }
                        } else {
                            isSuccess = true;
                            log.info("Step 4 SUCCESS: Appointment upload succeeded (no code field in response)");
                        }
                    } else if (!response.getStatusCode().is2xxSuccessful()) {
                        log.warn("Step 4 ERROR: Appointment upload failed for patient ID: {} - HTTP Status: {}",
                                patientId, response.getStatusCode());
                    }

                    if (isSuccess) {
                        successCount++;

                        if (firstSuccessfulAppointmentId == null && responseBody != null) {
                            String appointmentId = extractAppointmentIdFromResponse(responseBody);
                            if (appointmentId != null) {
                                firstSuccessfulAppointmentId = appointmentId;
                            }
                        }
                    } else {
                        failCount++;
                        log.warn("Step 4 FAILED: Appointment upload failed for patient ID: {} - Response: {}",
                                patientId, responseBody);
                    }
                } catch (Exception e) {
                    failCount++;
                    log.error("Step 4 ERROR: Exception uploading appointment for patient ID: {} - {}",
                            patientId, e.getMessage(), e);
                }
            }

            result.put("appointmentUploadStatus", HttpStatus.OK.value());
            result.put("appointmentCount", appointmentData.getAppointments().size());
            result.put("appointmentSuccessCount", successCount);
            result.put("appointmentFailCount", failCount);

            log.info("=== UPLOAD APPOINTMENTS - COMPLETE ===");

            if (failCount > 0) {
                log.warn("WARNING: {} appointment(s) failed to upload. Check logs above for details.", failCount);
            }
            if (successCount == 0 && appointmentData.getAppointments().size() > 0) {
                log.error("ERROR: All {} appointment(s) failed to upload!", appointmentData.getAppointments().size());
            }

            return firstSuccessfulAppointmentId;
        } catch (Exception e) {
            result.put("appointmentUploadStatus", HttpStatus.INTERNAL_SERVER_ERROR.value());
            result.put("appointmentUploadError", e.getMessage());
            log.error("Step 4 ERROR: Exception processing appointments for patient ID: {} - {}",
                    patientId, e.getMessage(), e);
            return null;
        }
    }

    private String extractAppointmentIdFromResponse(Map<String, Object> responseBody) {
        try {
            if (responseBody.containsKey("data")) {
                Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
                if (data != null) {
                    if (data.containsKey("appointment_id")) {
                        return String.valueOf(data.get("appointment_id"));
                    } else if (data.containsKey("id")) {
                        return String.valueOf(data.get("id"));
                    }
                }
            } else if (responseBody.containsKey("appointment_id")) {
                return String.valueOf(responseBody.get("appointment_id"));
            } else if (responseBody.containsKey("id")) {
                return String.valueOf(responseBody.get("id"));
            }
        } catch (Exception e) {
            log.warn("Failed to extract appointment_id from response: {}", e.getMessage());
        }
        return null;
    }

    // Helper methods to build request payloads

    private PatientInformation extractPatientInformation(PersonalDetailsData personalDetails) {
        if (personalDetails != null && personalDetails.getResponse() != null
                && personalDetails.getResponse().getPatientInformation() != null) {
            Map<String, PatientInformation> patientInfoMap = personalDetails.getResponse().getPatientInformation();
            return patientInfoMap.values().stream()
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private Map<String, Object> buildPatientRequest(PatientInformation patientInfo, String clinicId) {
        Map<String, Object> request = new LinkedHashMap<>();

        request.put("first_name", patientInfo.getFirstName() != null ? patientInfo.getFirstName() : "");
        request.put("middle_name", patientInfo.getMiddleName() != null ? patientInfo.getMiddleName() : "");
        request.put("last_name", patientInfo.getLastName() != null ? patientInfo.getLastName() : "");
        request.put("suffix", "");
        request.put("alias", "");
        request.put("email_address", patientInfo.getEmail() != null ? patientInfo.getEmail() : "");
        request.put("mobile", "");
        request.put("date_of_birth", patientInfo.getBirthDate() != null ? patientInfo.getBirthDate() : "");
        request.put("occupetion", "");
        request.put("legacy_patient", false);

        // Address
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("postal_code", patientInfo.getZipCode() != null ? patientInfo.getZipCode() : "");
        address.put("line1", patientInfo.getAddressLine1() != null ? patientInfo.getAddressLine1() : "");
        address.put("line2", patientInfo.getAddressLine2() != null ? patientInfo.getAddressLine2() : "");
        address.put("city", patientInfo.getCity() != null ? patientInfo.getCity() : "");
        address.put("state", patientInfo.getState() != null ? patientInfo.getState() : "");
        address.put("stateDetails", "");
        request.put("address", address);

        request.put("clinic_id", clinicId);

        return request;
    }

    private Map<String, Object> buildInsuranceRequest(InsuranceDetails insurance, String patientId) {
        Map<String, Object> request = new LinkedHashMap<>();

        request.put("group_number", "");

        String payerType = "9"; // Default to "9" for others
        if (insurance.getInsurance_payer() != null &&
            StringUtils.hasText(insurance.getInsurance_payer().getPayer_id())) {
            try {
                int payerId = Integer.parseInt(insurance.getInsurance_payer().getPayer_id());
                if (payerId == 1) {
                    payerType = "MEDICARE";
                } else if (payerId == 2) {
                    payerType = "MEDICAID";
                } else {
                    payerType = "9"; // For payer_id 9 or any other value
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid payer_id format: {}, using default payer_type: 9",
                        insurance.getInsurance_payer().getPayer_id());
                payerType = "9";
            }
        }
        request.put("payer_type", payerType);

        request.put("insurance_card_id", null);
        request.put("insurance_number", "");
        request.put("insurance_file", null);

        Map<String, Object> insuranceFile = new LinkedHashMap<>();
        insuranceFile.put("file_name_front", "");
        insuranceFile.put("file_name_back", "");
        insuranceFile.put("file_url_front", "");
        insuranceFile.put("file_url_back", "");
        request.put("insurance_file", insuranceFile);

        request.put("medicare_secondary_reason_code", "");

        try {
            request.put("patient_id", Integer.parseInt(patientId));
        } catch (NumberFormatException e) {
            request.put("patient_id", patientId);
        }

        if (insurance.getInsurance_payer() != null) {
            if (StringUtils.hasText(insurance.getInsurance_payer().getPayer_id())) {
                try {
                    request.put("payer_reg_id", Integer.parseInt(insurance.getInsurance_payer().getPayer_id()));
                } catch (NumberFormatException e) {
                    request.put("payer_reg_id", insurance.getInsurance_payer().getPayer_id());
                }
            }

            Map<String, Object> payerAlias = new LinkedHashMap<>();
            payerAlias.put("alias_id", insurance.getInsurance_payer().getPayer_id() != null
                    ? insurance.getInsurance_payer().getPayer_id() : "");
            payerAlias.put("alias_name", insurance.getInsurance_payer().getName() != null
                    ? insurance.getInsurance_payer().getName() : "");
            request.put("payer_alias_details", payerAlias);
        }

        request.put("plan_start_date", insurance.getPlan_start_date() != null ? insurance.getPlan_start_date() : "");
        request.put("plan_end_date", insurance.getPlan_end_date() != null ? insurance.getPlan_end_date() : null);
        request.put("insurance_card_type", "PRIMARY");
        request.put("plan_type", "COMMERCIAL_PPO");
        request.put("insurance_used_for", "HEALTH_INSURANCE");

        return request;
    }

    private Map<String, Object> buildPersonalDetailsRequest(PersonalDetailsData personalDetails,
                                                           String patientId, String clinicId, String digestDefinitionId) {
        Map<String, Object> request = new LinkedHashMap<>();

        int organisationId;
        try {
            organisationId = Integer.parseInt(clinicId);
        } catch (NumberFormatException e) {
            organisationId = 762;
        }
        request.put("organisation_id", organisationId);

        int patientIdInt;
        try {
            patientIdInt = Integer.parseInt(patientId);
        } catch (NumberFormatException e) {
            patientIdInt = 0;
        }
        request.put("patient_id", patientIdInt);

        request.put("form_name", "PERSONAL-DETAILS");
        request.put("digest_definition_id", digestDefinitionId);

        // Metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("soap_context_id", "");
        metadata.put("form_id", "EMMACARE_FORM_" + System.currentTimeMillis());
        metadata.put("version", 92);
        metadata.put("exist_dot", true);
        request.put("metadata", metadata);

        if (personalDetails.getResponse() != null) {
            request.put("response", convertResponseToMap(personalDetails.getResponse()));
        } else {
            request.put("response", new LinkedHashMap<>());
        }

        request.put("mandatory_fields_completed", false);
        request.put("version", 0);

        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertResponseToMap(PersonalDetailsResponseBlock response) {
        Map<String, Object> responseMap = new LinkedHashMap<>();

        if (response.getPatientInformation() != null) {
            Map<String, Map<String, Object>> patientInfoMap = new LinkedHashMap<>();
            for (Map.Entry<String, PatientInformation> entry : response.getPatientInformation().entrySet()) {
                Map<String, Object> patientInfoAsMap = objectMapper.convertValue(entry.getValue(), Map.class);
                patientInfoMap.put(entry.getKey(), patientInfoAsMap);
            }
            responseMap.put("Patient information", patientInfoMap);
        }

        if (response.getCareTeamMembers() != null) {
            responseMap.put("Care Team Members((Primary Care Provider, (Professional nurse)",
                    response.getCareTeamMembers());
        }

        return responseMap;
    }

    private String extractPatientIdFromResponse(Map<String, Object> responseBody) {
        if (responseBody.containsKey("data")) {
            Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
            if (data != null && data.containsKey("patient_id")) {
                return String.valueOf(data.get("patient_id"));
            }
        } else if (responseBody.containsKey("patient_id")) {
            return String.valueOf(responseBody.get("patient_id"));
        }
        return null;
    }

    public String getSubmissionIdByPatientId(String patientId) {
        Map<String, String> result = getPersonalDetailsInfo(patientId);
        return result != null ? result.get("submissionId") : null;
    }
    public String getDigestDefinitionIdByPatientId(String patientId) {
        Map<String, String> result = getPersonalDetailsInfo(patientId);
        return result != null ? result.get("digestDefinitionId") : null;
    }


    private Map<String, String> getPersonalDetailsInfo(String patientId) {
        String url = apiBaseUrl + "/personal-details"
                + "?patientId=" + patientId + "&patient_id=" + patientId;

        String token = ehrTokenService.getAccessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<PersonalDetailsResponse> response =
                    restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            entity,
                            new ParameterizedTypeReference<PersonalDetailsResponse>() {}
                    );

            if (response.getBody() != null && response.getBody().getData() != null) {
                PersonalDetailsData data = response.getBody().getData();
                Map<String, String> result = new LinkedHashMap<>();
                
                if (data.getSubmissionId() != null) {
                    result.put("submissionId", data.getSubmissionId());
                }
                
                if (data.getDigestDefinitionId() != null) {
                    result.put("digestDefinitionId", data.getDigestDefinitionId());
                }
                
                return result;
            }
        } catch (Exception e) {
            log.error("Error fetching personal details info for patient ID {}: {}", patientId, e.getMessage(), e);
        }

        return null;
    }

    private Map<String, Object> buildAppointmentRequest(Appointment appointment, String patientId, String clinicIdStr, String caseId, int doctorId) {
        log.info("--- buildAppointmentRequest START ---");
        
        Map<String, Object> request = new LinkedHashMap<>();

        try {
            request.put("patient_id", Integer.parseInt(patientId));
            log.info("Set patient_id: {}", request.get("patient_id"));
        } catch (NumberFormatException e) {
            log.warn("Invalid patient_id format: {}, using as-is", patientId);
            request.put("patient_id", patientId);
        }
        
        int clinicId = 762; // Default
        request.put("clinic_id", clinicId);
        
        request.put("doctor_id", doctorId);

        long timestamp = 0;
        long endTimestamp = 0;
        
        log.info("Processing date_time: '{}'", appointment.getDate_time());
        if (appointment.getDate_time() != null && !appointment.getDate_time().isEmpty()) {
            try {
                if (appointment.getDate_time().matches("\\d+")) {
                    timestamp = Long.parseLong(appointment.getDate_time());

                    java.time.Instant instant = java.time.Instant.ofEpochSecond(timestamp);
                    ZonedDateTime zdtUTC = instant.atZone(java.time.ZoneId.of("UTC"));
                    ZonedDateTime zdtLocal = instant.atZone(java.time.ZoneId.systemDefault());

                } else {
                    timestamp = System.currentTimeMillis() / 1000; // Fallback to current time
                }
            } catch (NumberFormatException e) {
                log.error("Failed to parse appointment date_time as epoch: {}", appointment.getDate_time(), e);
                timestamp = System.currentTimeMillis() / 1000; // Fallback
            }
        } else {
            log.warn("Appointment has no date_time, using current time as fallback");
            timestamp = System.currentTimeMillis() / 1000;
        }
        
        log.info("Processing end_date_time: '{}'", appointment.getEnd_date_time());
        if (appointment.getEnd_date_time() != null && !appointment.getEnd_date_time().isEmpty()) {
            try {
                if (appointment.getEnd_date_time().matches("\\d+")) {
                    endTimestamp = Long.parseLong(appointment.getEnd_date_time());
                    log.info("Parsed end_date_time as epoch: {} -> {}", appointment.getEnd_date_time(), endTimestamp);
                } else {
                    log.warn("Appointment end_date_time is not epoch format: {}, using start + 30min", appointment.getEnd_date_time());
                    endTimestamp = timestamp + 1800; // 30 minutes in seconds
                }
            } catch (NumberFormatException e) {
                log.error("Failed to parse appointment end_date_time as epoch: {}", appointment.getEnd_date_time(), e);
                endTimestamp = timestamp + 1800; // Default 30 minutes duration
            }
        } else {
            log.info("No end_date_time provided, using start + 30 minutes");
            endTimestamp = timestamp + 1800;
        }
        
        request.put("timestamp", timestamp);
        request.put("end_timestamp", endTimestamp);

        List<Integer> appointmentCategoryIds = new ArrayList<>();
        if (appointment.getCategory() != null && !appointment.getCategory().isEmpty()) {
            log.info("Processing {} category(ies)", appointment.getCategory().size());
            for (AppointmentCategory category : appointment.getCategory()) {
                if (category != null) {
                    int categoryId = mapCategoryNameToId(category.getName());
                    appointmentCategoryIds.add(categoryId);
                    log.info("Mapped category '{}' to ID: {}", category.getName(), categoryId);
                }
            }
        }
        
        if (appointmentCategoryIds.isEmpty()) {
            appointmentCategoryIds.add(2112); // Default category ID (Initial Evaluation)
            log.info("No categories found, using default category ID: 2112");
        }
        
        request.put("appointment_category", appointmentCategoryIds);

        // Optional fields
        request.put("payment_mode", "SELF_PAY"); // Default payment mode
        if (caseId != null && !caseId.isEmpty() && !caseId.startsWith("EMMACARE_CASE_")) {
            request.put("emmacare_case_id", caseId);
        } else if (caseId != null && !caseId.isEmpty()) {
            request.put("emmacare_case_id", caseId);
        } else {
            String fallbackCaseId = "EMMACARE_CASE_" + UUID.randomUUID().toString().replace("-", "");
            request.put("emmacare_case_id", fallbackCaseId);
        }
        request.put("comments", "");
        request.put("insurance_used_for", null);
        request.put("tele_health_url", null);
        
        log.info("--- buildAppointmentRequest COMPLETE ---");

        return request;
    }

    private int mapCategoryNameToId(String categoryName) {
        if (categoryName == null || categoryName.isEmpty()) {
            return 2112; // Default to Initial Evaluation
        }
        
        String nameUpper = categoryName.toUpperCase().trim();
        
        if (nameUpper.contains("INITIAL") || nameUpper.contains("EVALUATION")) {
            return 2112; // Initial Evaluation
        } else if (nameUpper.contains("FOLLOW") || nameUpper.contains("FOLLOW-UP") || nameUpper.contains("FOLLOW UP")) {
            return 2113; // Follow-up
        } else {
            // Default to Initial Evaluation if unknown
            log.debug("Unknown category name '{}', defaulting to 2112 (Initial Evaluation)", categoryName);
            return 2112;
        }
    }

    private Map<String, String> createInitialSoapContext(String patientId, String appointmentId, String caseId,
                                                       HttpHeaders headers, Map<String, Object> result) {
        Map<String, String> soapContextInfo = new LinkedHashMap<>();
        try {
            log.info("=== CREATE INITIAL SOAP CONTEXT - START ===");
            log.info("Patient ID: {}, Appointment ID: {}, Case ID: {}", patientId, appointmentId, caseId);
            
            // Build SOAP context creation request matching curl example
            Map<String, Object> soapContextRequest = new LinkedHashMap<>();
            try {
                soapContextRequest.put("patient_id", Integer.parseInt(patientId));
            } catch (NumberFormatException e) {
                soapContextRequest.put("patient_id", patientId);
            }

            try {
                soapContextRequest.put("appointment_id", Integer.parseInt(appointmentId));
            } catch (NumberFormatException e) {
                soapContextRequest.put("appointment_id", appointmentId);
            }
            
            String entryDateTime = java.time.LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
            soapContextRequest.put("entry_date_time", entryDateTime);
            log.info("Set entry_date_time: {}", entryDateTime);
            
            soapContextRequest.put("type", "INITIAL_SOAP");
            soapContextRequest.put("attributes", new LinkedHashMap<>());
            soapContextRequest.put("category", "MSK");
            soapContextRequest.put("soap_template_id", null);
            soapContextRequest.put("signature", "[]");
            

            String soapContextApiUrl = apiBaseUrl + "/soap-context";
            
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(soapContextRequest, headers);
            log.info("Step 5.5: Sending POST request to create initial SOAP context: {}", soapContextApiUrl);
            
            ResponseEntity<Map> response = restTemplate.exchange(
                    soapContextApiUrl,
                    HttpMethod.POST,
                    requestEntity,
                    Map.class
            );
            
            Map<String, Object> responseBody = response.getBody();

            if (response.getStatusCode().is2xxSuccessful() && responseBody != null) {
                // Check for error codes
                Object codeObj = responseBody.get("code");
                boolean isSuccess = false;
                if (codeObj != null) {
                    int code = 0;
                    try {
                        if (codeObj instanceof Integer) {
                            code = (Integer) codeObj;
                        } else if (codeObj instanceof String) {
                            code = Integer.parseInt((String) codeObj);
                        } else {
                            code = Integer.parseInt(String.valueOf(codeObj));
                        }
                        
                        if (code == 0 || code == 200 || code == 2000 || code == 20000) {
                            isSuccess = true;
                            log.info("Step 5.5: SOAP context creation returned success code: {}", code);
                        } else {
                            String message = responseBody.get("message") != null
                                    ? String.valueOf(responseBody.get("message")) 
                                    : "Unknown error";
                            log.warn("Step 5.5 WARNING: SOAP context creation returned error code: {}, message: {}", code, message);
                        }
                    } catch (NumberFormatException e) {
                        isSuccess = true; // Assume success if can't parse
                    }
                } else {
                    isSuccess = true; // No code field - assume success if HTTP status is 2xx
                }
                
                if (isSuccess) {
                    String assessmentSubmissionId = extractAssessmentSubmissionIdFromResponse(responseBody);
                    String contextId = extractContextIdFromResponse(responseBody);
                    
                    if (assessmentSubmissionId != null) {
                        soapContextInfo.put("assessmentSubmissionId", assessmentSubmissionId);
                        log.info("Step 5.5 SUCCESS: Extracted assessment_submission_id: {} from SOAP context creation response", 
                                assessmentSubmissionId);
                    } else {
                        log.warn("Step 5.5 WARNING: No assessment_submission_id found in SOAP context creation response");
                    }
                    
                    if (contextId != null) {
                        soapContextInfo.put("contextId", contextId);
                    } else {
                    }
                    
                    result.put("soapContextCreationStatus", "SUCCESS");
                } else {
                    result.put("soapContextCreationStatus", "FAILED");
                    result.put("soapContextCreationError", responseBody != null ? responseBody.toString() : "Unknown error");
                    log.error("Step 5.5 ERROR: SOAP context creation failed for patient ID: {} - Response: {}", 
                            patientId, responseBody);
                }
            } else {
                result.put("soapContextCreationStatus", "FAILED");
                result.put("soapContextCreationError", "HTTP Status: " + response.getStatusCode());
                log.error("Step 5.5 ERROR: SOAP context creation failed - HTTP Status: {}", response.getStatusCode());
            }
            
            log.info("=== CREATE INITIAL SOAP CONTEXT - COMPLETE ===");
            return soapContextInfo;
            
        } catch (Exception e) {
            result.put("soapContextCreationStatus", "ERROR");
            result.put("soapContextCreationError", e.getMessage());
            log.error("Step 5.5 ERROR: Exception creating initial SOAP context for patient ID: {} - {}",
                    patientId, e.getMessage(), e);
            return soapContextInfo;
        }
    }
    
    private String extractAssessmentSubmissionIdFromResponse(Map<String, Object> responseBody) {
        try {
            if (responseBody.containsKey("data")) {
                Object dataObj = responseBody.get("data");
                if (dataObj instanceof Map) {
                    Map<String, Object> data = (Map<String, Object>) dataObj;
                    if (data.containsKey("assessment_submission_id")) {
                        return String.valueOf(data.get("assessment_submission_id"));
                    } else if (data.containsKey("submission_id")) {
                        return String.valueOf(data.get("submission_id"));
                    } else if (data.containsKey("id")) {
                        return String.valueOf(data.get("id"));
                    }
                }
            } else if (responseBody.containsKey("assessment_submission_id")) {
                return String.valueOf(responseBody.get("assessment_submission_id"));
            } else if (responseBody.containsKey("submission_id")) {
                return String.valueOf(responseBody.get("submission_id"));
            }
        } catch (Exception e) {
            log.warn("Failed to extract assessment_submission_id from response: {}", e.getMessage());
        }
        return null;
    }
    
    private String extractContextIdFromResponse(Map<String, Object> responseBody) {
        try {
            if (responseBody.containsKey("data")) {
                Object dataObj = responseBody.get("data");
                if (dataObj instanceof Map) {
                    Map<String, Object> data = (Map<String, Object>) dataObj;
                    if (data.containsKey("context_id")) {
                        return String.valueOf(data.get("context_id"));
                    } else if (data.containsKey("soap_context_id")) {
                        return String.valueOf(data.get("soap_context_id"));
                    } else if (data.containsKey("id")) {
                        return String.valueOf(data.get("id"));
                    }
                }
            } else if (responseBody.containsKey("context_id")) {
                return String.valueOf(responseBody.get("context_id"));
            } else if (responseBody.containsKey("soap_context_id")) {
                return String.valueOf(responseBody.get("soap_context_id"));
            }
        } catch (Exception e) {
            log.warn("Failed to extract context_id from response: {}", e.getMessage());
        }
        return null;
    }

    private void uploadSoapDetails(String patientId, String soapContextId, FormResponse formResponse,
                                   HttpHeaders headers, Map<String, Object> result) {
        String formDataApiUrl = apiBaseUrl + "/form-data";
        
        String digestDefinitionId = "618d3b29c23bec5eb18aae8b";
        
        try {
            log.info("Step 6: Uploading Assessment & Intervention form data for patient ID: {}, SOAP context ID: {}", 
                    patientId, soapContextId);
            
            if (formResponse == null) {
                log.warn("Step 6 WARNING: FormResponse is null, skipping form data upload");
                result.put("formDataUploadStatus", "SKIPPED - No form data");
                return;
            }
            
            Map<String, Object> formDataRequest = buildFormDataRequest(patientId, soapContextId, 
                    digestDefinitionId, formResponse);
            
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(formDataRequest, headers);
            log.info("Step 6: Sending POST request to upload form data: {}", formDataApiUrl);
            
            ResponseEntity<Map> response = restTemplate.exchange(
                    formDataApiUrl,
                    HttpMethod.POST,
                    requestEntity,
                    Map.class
            );
            
            Map<String, Object> responseBody = response.getBody();
            log.info("Step 6: Form data upload response - HTTP Status: {}, Response Body: {}", 
                    response.getStatusCode(), responseBody);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                // Check for error codes in response
                Object codeObj = responseBody != null ? responseBody.get("code") : null;
                boolean isSuccess = false;
                if (codeObj != null) {
                    int code = 0;
                    try {
                        if (codeObj instanceof Integer) {
                            code = (Integer) codeObj;
                        } else if (codeObj instanceof String) {
                            code = Integer.parseInt((String) codeObj);
                        } else {
                            code = Integer.parseInt(String.valueOf(codeObj));
                        }
                        
                        if (code == 0 || code == 200 || code == 2000 || code == 20000) {
                            isSuccess = true;
                            log.info("Step 6 SUCCESS: Form data upload succeeded - Response code: {}", code);
                        } else {
                            String message = responseBody != null && responseBody.get("message") != null
                                    ? String.valueOf(responseBody.get("message")) 
                                    : "Unknown error";
                            log.warn("Step 6 WARNING: Form data upload returned error code: {}, message: {}", code, message);
                        }
                    } catch (NumberFormatException e) {
                        log.warn("Step 6 WARNING: Could not parse response code: {}", codeObj);
                        isSuccess = true; // Assume success if can't parse
                    }
                } else {
                    isSuccess = true; // No code field - assume success if HTTP status is 2xx
                }
                
                if (isSuccess) {
                    result.put("formDataUploadStatus", "SUCCESS");
                    result.put("formDataUploadResponse", responseBody);
                    log.info("Step 6 SUCCESS: Assessment & Intervention form data uploaded successfully for patient ID: {}", patientId);
                } else {
                    result.put("formDataUploadStatus", "WARNING");
                    result.put("formDataUploadError", responseBody != null ? responseBody.toString() : "Unknown error");
                    log.warn("Step 6 WARNING: Form data upload completed with warnings for patient ID: {}", patientId);
                }
            } else {
                result.put("formDataUploadStatus", "FAILED");
                result.put("formDataUploadError", "HTTP Status: " + response.getStatusCode());
                log.error("Step 6 ERROR: Form data upload failed - HTTP Status: {}", response.getStatusCode());
            }
        } catch (Exception e) {
            result.put("formDataUploadStatus", "ERROR");
            result.put("formDataUploadError", e.getMessage());
            log.error("Step 6 ERROR: Exception uploading form data for patient ID: {} - {}", 
                    patientId, e.getMessage(), e);
        }
    }
    
    private Map<String, Object> buildFormDataRequest(String patientId, String soapContextId, 
                                                     String digestDefinitionId, FormResponse formResponse) {
        Map<String, Object> request = new LinkedHashMap<>();

        try {
            request.put("patient_id", Integer.parseInt(patientId));
        } catch (NumberFormatException e) {
            request.put("patient_id", patientId);
        }
        
        request.put("form_name", "ASSESSMENT");
        
        request.put("digest_definition_id", digestDefinitionId);
        
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("soap_context_id", soapContextId);
        
        List<Map<String, Object>> versions = new ArrayList<>();
        Map<String, Object> versionEntry = new LinkedHashMap<>();
        versionEntry.put("form_id", "EMMACARE_FORM_" + System.currentTimeMillis());
        versionEntry.put("version", 20);
        versions.add(versionEntry);
        metadata.put("versions", versions);
        
        metadata.put("soap_template", new LinkedHashMap<>());
        
        request.put("metadata", metadata);
        
        Map<String, Object> responseMap = new LinkedHashMap<>();
        
        // Add Assessment section
        if (formResponse.getAssessment() != null && !formResponse.getAssessment().isEmpty()) {
            Map<String, Map<String, Object>> assessmentMap = new LinkedHashMap<>();
            for (Map.Entry<String, CodeSection> entry : formResponse.getAssessment().entrySet()) {
                Map<String, Object> codeSectionMap = new LinkedHashMap<>();
                CodeSection codeSection = entry.getValue();
                
                codeSectionMap.put("icd_codes", new ArrayList<>());
                
                Object snomedCodes = codeSection.getSnomedCodes();
                List<Map<String, Object>> snomedCodesArray = convertCodesToArray(snomedCodes);
                codeSectionMap.put("snomed_codes", snomedCodesArray);
                log.debug("Assessment entry {} - snomed_codes type: {}, converted to array size: {}",
                        entry.getKey(), snomedCodes != null ? snomedCodes.getClass().getName() : "null", snomedCodesArray.size());
                
                Object loincCodes = codeSection.getLoincCodes();
                List<Map<String, Object>> loincCodesArray = convertCodesToArray(loincCodes);
                codeSectionMap.put("loinc_codes", loincCodesArray);
                log.debug("Assessment entry {} - loinc_codes type: {}, converted to array size: {}",
                        entry.getKey(), loincCodes != null ? loincCodes.getClass().getName() : "null", loincCodesArray.size());
                
                assessmentMap.put(entry.getKey(), codeSectionMap);
            }
            responseMap.put("Assessment", assessmentMap);
        } else {
            responseMap.put("Assessment", new LinkedHashMap<>());
        }
        
        if (formResponse.getIntervention() != null && !formResponse.getIntervention().isEmpty()) {
            Map<String, Map<String, Object>> interventionMap = new LinkedHashMap<>();
            for (Map.Entry<String, CodeSection> entry : formResponse.getIntervention().entrySet()) {
                Map<String, Object> codeSectionMap = new LinkedHashMap<>();
                CodeSection codeSection = entry.getValue();
                
                codeSectionMap.put("icd_codes", new ArrayList<>());
                
                Object snomedCodes = codeSection.getSnomedCodes();
                List<Map<String, Object>> snomedCodesArray = convertCodesToArray(snomedCodes);
                codeSectionMap.put("snomed_codes", snomedCodesArray);
                
                Object loincCodes = codeSection.getLoincCodes();
                List<Map<String, Object>> loincCodesArray = convertCodesToArray(loincCodes);
                codeSectionMap.put("loinc_codes", loincCodesArray);
                
                interventionMap.put(entry.getKey(), codeSectionMap);
            }
            responseMap.put("Intervention", interventionMap);
        } else {
            responseMap.put("Intervention", new LinkedHashMap<>());
        }
        
        request.put("response", responseMap);
        
        log.debug("Built form data request payload: {}", request);
        
        return request;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> convertCodesToArray(Object codes) {
        if (codes == null) {
            return new ArrayList<>();
        }
        
        if (codes instanceof String) {
            String codeStr = (String) codes;
            if (codeStr.isEmpty()) {
                return new ArrayList<>();
            }
            log.warn("Unexpected non-empty string in codes: {}", codeStr);
            return new ArrayList<>();
        }
        
        if (codes instanceof List) {
            List<Object> codeList = (List<Object>) codes;
            if (codeList.isEmpty()) {
                return new ArrayList<>();
            }
            
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object codeObj : codeList) {
                try {
                    Map<String, Object> codeMap = objectMapper.convertValue(codeObj, Map.class);
                    result.add(codeMap);
                } catch (Exception e) {
                    log.warn("Failed to convert code object to Map: {}, object type: {}",
                            e.getMessage(), codeObj != null ? codeObj.getClass().getName() : "null");
                    if (codeObj instanceof Map) {
                        result.add((Map<String, Object>) codeObj);
                    }
                }
            }
            return result;
        }
        
        try {
            if (codes instanceof Map) {
                List<Map<String, Object>> result = new ArrayList<>();
                result.add((Map<String, Object>) codes);
                return result;
            }
            
            Map<String, Object> codeMap = objectMapper.convertValue(codes, Map.class);
            List<Map<String, Object>> result = new ArrayList<>();
            result.add(codeMap);
            return result;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private Integer createProvider(DoctorDetailsData providerDetails) {
        try {
            String token = ehrTokenService.getAccessToken();
            String providerUrl = apiBaseUrl + "/clinic/add-clinic-staff";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("sec-ch-ua-platform", "\"Linux\"");
            headers.set("X-Device-ID", "f1f01e24-b529-4943-8e33-f00b9d35cc3c");

            // Build request
            ProviderCreationRequest request = new ProviderCreationRequest();
            ProviderCreationRequest.UserDetails userDetails = new ProviderCreationRequest.UserDetails();
            
            userDetails.setFirstName(providerDetails.getFirst_name() != null ? providerDetails.getFirst_name() : "temp");
            userDetails.setMiddleName(providerDetails.getMiddle_name() != null ? providerDetails.getMiddle_name() : "");
            userDetails.setLastName(providerDetails.getLast_name() != null ? providerDetails.getLast_name() : "temp");
            
            // Generate username from email or name
            String username = providerDetails.getEmail();
            if (username == null || username.isEmpty()) {
                username = (providerDetails.getFirst_name() != null ? providerDetails.getFirst_name().toLowerCase() : "temp") +
                          (providerDetails.getLast_name() != null ? providerDetails.getLast_name().toLowerCase() : "temp") +
                          System.currentTimeMillis() + "@gmail.in";
            }
            userDetails.setUsername(username);
            userDetails.setEmail(username);
            
            // Get clinic ID list - try to get from clinics list or use default
            List<Integer> clinicIdList = new ArrayList<>();
            if (providerDetails.getClinics() != null && !providerDetails.getClinics().isEmpty()) {
                clinicIdList.add(providerDetails.getClinics().get(0).getClinic_id());
            } else {
                clinicIdList.add(762); // Default clinic ID
            }
            userDetails.setClinicIdList(clinicIdList);
            
            userDetails.setRoles(List.of("DOCTOR"));
            userDetails.setMobile(providerDetails.getMobile() != null ? providerDetails.getMobile() : "+12015550123");
            userDetails.setSearch("");
            
            request.setUserDetails(userDetails);

            HttpEntity<ProviderCreationRequest> requestEntity = new HttpEntity<>(request, headers);
            ResponseEntity<String> response = restTemplate.exchange(providerUrl, HttpMethod.POST, requestEntity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                ProviderCreationResponse responseDto = objectMapper.readValue(response.getBody(), ProviderCreationResponse.class);
                if (responseDto.getCode() != null && responseDto.getCode() == 2000 && responseDto.getData() != null) {
                    Integer doctorId = responseDto.getData().getId();
                    log.info("Successfully created provider with doctor ID: {}", doctorId);
                    return doctorId;
                } else {
                    log.warn("Provider creation returned non-success code: {}", responseDto.getCode());
                }
            } else {
                log.warn("Failed to create provider - Status: {}, Body: {}", response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("Error creating provider: {}", e.getMessage(), e);
        }
        return null;
    }

     // Update provider with TIN, NPI, and CCN
    private void updateProviderDetails(Integer doctorId, DoctorDetailsData providerDetails) {
        try {
            String token = ehrTokenService.getAccessToken();
            String providerUrl = apiBaseUrl + "/doctor/" + doctorId;
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("sec-ch-ua-platform", "\"Linux\"");
            headers.set("X-Device-ID", "f1f01e24-b529-4943-8e33-f00b9d35cc3c");

            // First, fetch existing doctor details
            DoctorDetailsData existingDoctor = fetchDoctorDetails(doctorId);
            if (existingDoctor == null) {
                log.warn("Could not fetch existing doctor details for update, skipping");
                return;
            }

            // Build update request
            ProviderUpdateRequest request = new ProviderUpdateRequest();
            request.setId(doctorId);
            request.setDoctorId(doctorId);
            request.setFirstName(providerDetails.getFirst_name() != null ? providerDetails.getFirst_name() : existingDoctor.getFirst_name());
            request.setMiddleName(providerDetails.getMiddle_name() != null ? providerDetails.getMiddle_name() : existingDoctor.getMiddle_name());
            request.setLastName(providerDetails.getLast_name() != null ? providerDetails.getLast_name() : existingDoctor.getLast_name());
            request.setRoles(existingDoctor.getRoles() != null ? existingDoctor.getRoles() : List.of("DOCTOR"));
            request.setPhotoUrl(existingDoctor.getPhoto_url());
            request.setSex(existingDoctor.getSex());
            request.setMasterSpecialization(existingDoctor.getMaster_specialization());
            request.setMobile(providerDetails.getMobile() != null ? providerDetails.getMobile() : existingDoctor.getMobile());
            request.setEmail(providerDetails.getEmail() != null ? providerDetails.getEmail() : existingDoctor.getEmail());
            request.setAlternativeEmail(existingDoctor.getEmail());
            request.setOrganisationId(690); // Default organisation ID
            
            // Set NPI, TIN, CCN from extracted provider details
            request.setNpi(providerDetails.getNpi() != null ? providerDetails.getNpi() : existingDoctor.getNpi());
            request.setTaxIdNumber(providerDetails.getTax_id_number() != null ? providerDetails.getTax_id_number() : existingDoctor.getTax_id_number());
            request.setCmsCertificateNumber(providerDetails.getCms_certificate_number() != null ? providerDetails.getCms_certificate_number() : existingDoctor.getCms_certificate_number());
            
            request.setResidentialAddress(existingDoctor.getResidential_address());
            request.setClinics(existingDoctor.getClinics());

            HttpEntity<ProviderUpdateRequest> requestEntity = new HttpEntity<>(request, headers);
            ResponseEntity<String> response = restTemplate.exchange(providerUrl, HttpMethod.POST, requestEntity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Successfully updated provider {} with NPI: {}, TIN: {}, CCN: {}", 
                        doctorId, request.getNpi(), request.getTaxIdNumber(), request.getCmsCertificateNumber());
            } else {
                log.warn("Failed to update provider - Status: {}, Body: {}", response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.error("Error updating provider details: {}", e.getMessage(), e);
        }
    }

    // QRDA-III Generation Methods

    private Integer extractDoctorIdFromPatients(List<String> patientIds) {
        try {
            if (patientIds.isEmpty()) {
                return null;
            }
            
            // Fetch first patient to get appointment data
            List<PatientMeasureData> patients = patientSummaryService.fetchPatients(List.of(patientIds.get(0))).stream()
                    .filter(Objects::nonNull)
                    .filter(p -> p.getPatientId() != null)
                    .collect(Collectors.toList());
            
            if (patients.isEmpty()) {
                return null;
            }
            
            PatientMeasureData firstPatient = patients.get(0);
            AppointmentData appointmentData = firstPatient.getAppointmentData();
            
            // Try to extract doctorId from appointments
            if (appointmentData != null && appointmentData.getAppointments() != null && !appointmentData.getAppointments().isEmpty()) {
                Appointment firstAppointment = appointmentData.getAppointments().get(0);
                if (firstAppointment != null) {
                    // First, try to extract from doctor Map if available
                    if (firstAppointment.getDoctor() != null && !firstAppointment.getDoctor().isEmpty()) {
                        Object doctorIdObj = firstAppointment.getDoctor().get("doctor_id");
                        if (doctorIdObj != null) {
                            try {
                                Integer doctorId = null;
                                if (doctorIdObj instanceof Integer) {
                                    doctorId = (Integer) doctorIdObj;
                                } else if (doctorIdObj instanceof String) {
                                    doctorId = Integer.parseInt((String) doctorIdObj);
                                } else {
                                    doctorId = Integer.parseInt(String.valueOf(doctorIdObj));
                                }
                                log.info("Extracted doctorId {} from appointment doctor map", doctorId);
                                return doctorId;
                            } catch (NumberFormatException e) {
                                log.warn("Failed to parse doctorId from appointment doctor map: {}", e.getMessage());
                            }
                        }
                    }
                    
                    if (firstAppointment.getAppointment_id() > 0) {
                        try {
                            String token = ehrTokenService.getAccessToken();
                            String appointmentUrl = apiBaseUrl + "/appointment/" + firstAppointment.getAppointment_id();
                            
                            HttpHeaders headers = new HttpHeaders();
                            headers.setBearerAuth(token);
                            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                            
                            HttpEntity<Void> request = new HttpEntity<>(headers);
                            ResponseEntity<Map> response = restTemplate.exchange(appointmentUrl, HttpMethod.GET, request, Map.class);
                            
                            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                                Map<String, Object> responseBody = response.getBody();
                                Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
                                if (data != null) {
                                    Object doctorIdObj = data.get("doctor_id");
                                    if (doctorIdObj != null) {
                                        Integer doctorId = null;
                                        if (doctorIdObj instanceof Integer) {
                                            doctorId = (Integer) doctorIdObj;
                                        } else if (doctorIdObj instanceof String) {
                                            doctorId = Integer.parseInt((String) doctorIdObj);
                                        } else {
                                            doctorId = Integer.parseInt(String.valueOf(doctorIdObj));
                                        }
                                        log.info("Extracted doctorId {} from appointment API for appointment {}", doctorId, firstAppointment.getAppointment_id());
                                        return doctorId;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Failed to fetch doctorId from appointment API: {}", e.getMessage());
                        }
                    }
                }
            }
            
            return null;
        } catch (Exception e) {
            log.error("Error extracting doctorId from patients: {}", e.getMessage(), e);
            return null;
        }
    }

    private DoctorDetailsData createDefaultDoctorDetails() {
        DoctorDetailsData doctorDetails = new DoctorDetailsData();
        doctorDetails.setFirst_name("Provider");
        doctorDetails.setLast_name("Default");
        doctorDetails.setNpi("1234567890");
        doctorDetails.setTax_id_number("123456789");
        doctorDetails.setCms_certificate_number("123456");
        return doctorDetails;
    }

    private DoctorDetailsData fetchDoctorDetails(int doctorId) {
        try {
            String token = ehrTokenService.getAccessToken();

            String url = apiBaseUrl + "/doctor/" + doctorId;
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, request, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("Failed to fetch doctor details for doctorId: {} - Status: {}", doctorId, response.getStatusCode());
                return null;
            }

            DoctorDetailsResponse dto = objectMapper.readValue(response.getBody(), DoctorDetailsResponse.class);
            return dto.getData();

        } catch (Exception e) {
            log.error("Error fetching doctor details for doctorId: {} - {}", doctorId, e.getMessage(), e);
            return null;
        }
    }

    public byte[] generateQrdaIII(List<PatientMeasureData> patients, DoctorDetailsData doctorDetails, 
                                  String measurementPeriodStart, String measurementPeriodEnd) throws Exception {
        if (CollectionUtils.isEmpty(patients)) {
            throw new IllegalArgumentException("Patient list cannot be empty");
        }
        
        ClinicalDocument document = CDAFactory.eINSTANCE.createClinicalDocument();
        
        String clinicId = extractClinicId(patients);
        String clinicName = extractClinicName(patients);

        addDocumentHeader(document);
        addC2Author(document, clinicId, clinicName);
        addC2Custodian(document, patients, clinicId, clinicName);
        addC2InformationRecipient(document);
        addC2LegalAuthenticator(document, clinicId);
        addC2Participant(document);
        addC2DocumentationOf(document, doctorDetails);
        addC2RecordTargets(document, patients);
        addC2ReportingParametersSection(document);
        addC2MeasureSection(document, patients, measurementPeriodStart, measurementPeriodEnd);
        return serializeQrdaIII(document);
    }
    
    private String extractClinicId(List<PatientMeasureData> patients) {
        return patients.stream()
                .filter(p -> StringUtils.hasText(p.getClinicId()))
                .findFirst()
                .map(PatientMeasureData::getClinicId)
                .orElse("223344");
    }

    private String extractClinicName(List<PatientMeasureData> patients) {
        return "Good Health Clinic"; // Fallback until clinic name is available
    }

    private void addDocumentHeader(ClinicalDocument document) {
        CS realmCode = DatatypesFactory.eINSTANCE.createCS();
        realmCode.setCode("US");
        document.getRealmCodes().add(realmCode);

        InfrastructureRootTypeId typeId = CDAFactory.eINSTANCE.createInfrastructureRootTypeId();
        typeId.setRoot("2.16.840.1.113883.1.3");
        typeId.setExtension("POCD_HD000040");
        document.setTypeId(typeId);

        II templateId1 = DatatypesFactory.eINSTANCE.createII();
        templateId1.setRoot("2.16.840.1.113883.10.20.27.1.1");
        templateId1.setExtension("2020-12-01");
        document.getTemplateIds().clear();
        document.getTemplateIds().add(templateId1);
        
        II templateId2 = DatatypesFactory.eINSTANCE.createII();
        templateId2.setRoot("2.16.840.1.113883.10.20.27.1.2");
        templateId2.setExtension("2024-12-01"); // QRDA Category III Report - CMS (V9)
        document.getTemplateIds().add(templateId2);

        // Document ID
        II documentId = DatatypesFactory.eINSTANCE.createII();
        documentId.setRoot(UUID.randomUUID().toString());
        document.setId(documentId);

        // Code
        CE code = DatatypesFactory.eINSTANCE.createCE();
        code.setCode("55184-6");
        code.setCodeSystem("2.16.840.1.113883.6.1");
        code.setCodeSystemName("LOINC");
        code.setDisplayName("Quality Reporting Document Architecture Calculated Summary Report");
        document.setCode(code);

        ST title = DatatypesFactory.eINSTANCE.createST();
        title.addText("QRDA Calculated Summary Report");
        document.setTitle(title);

        TS effectiveTime = DatatypesFactory.eINSTANCE.createTS();
        effectiveTime.setValue(getC2CurrentTimestamp());
        document.setEffectiveTime(effectiveTime);

        CE confidentialityCode = DatatypesFactory.eINSTANCE.createCE();
        confidentialityCode.setCode("N");
        confidentialityCode.setCodeSystem("2.16.840.1.113883.5.25");
        document.setConfidentialityCode(confidentialityCode);

        CS languageCode = DatatypesFactory.eINSTANCE.createCS();
        languageCode.setCode("en");
        document.setLanguageCode(languageCode);

        INT versionNumber = DatatypesFactory.eINSTANCE.createINT();
        versionNumber.setValue(BigInteger.valueOf(1));
        document.setVersionNumber(versionNumber);
    }

    private void addC2Author(ClinicalDocument document, String clinicId, String clinicName) {
        Author author = CDAFactory.eINSTANCE.createAuthor();
        author.setTime(DatatypesFactory.eINSTANCE.createTS(getC2CurrentTimestamp()));

        AssignedAuthor assignedAuthor = CDAFactory.eINSTANCE.createAssignedAuthor();

        II authorId = DatatypesFactory.eINSTANCE.createII();
        authorId.setRoot("2.16.840.1.113883.4.6");
        authorId.setExtension("1982671962");
        assignedAuthor.getIds().add(authorId);

        AD address = DatatypesFactory.eINSTANCE.createAD();
        address.addStreetAddressLine("202 Burlington Rd.");
        address.addCity("Bedford");
        address.addState("MA");
        address.addPostalCode("01730");
        address.addCountry("US");
        assignedAuthor.getAddrs().add(address);

        TEL telecom = DatatypesFactory.eINSTANCE.createTEL();
        telecom.getUses().add(TelecommunicationAddressUse.WP);
        telecom.setValue("tel:(781)271-3000");
        assignedAuthor.getTelecoms().add(telecom);

        AuthoringDevice device = CDAFactory.eINSTANCE.createAuthoringDevice();
        SC manufacturerModelName = DatatypesFactory.eINSTANCE.createSC();
        manufacturerModelName.addText("TestSystem");
        device.setManufacturerModelName(manufacturerModelName);
        SC softwareName = DatatypesFactory.eINSTANCE.createSC();
        softwareName.addText("TestSystem");
        device.setSoftwareName(softwareName);
        assignedAuthor.setAssignedAuthoringDevice(device);

        Organization organization = CDAFactory.eINSTANCE.createOrganization();
        II orgId = DatatypesFactory.eINSTANCE.createII();
        orgId.setRoot("2.16.840.1.113883.19.5");
        orgId.setExtension("98765");
        organization.getIds().add(orgId);

        ON orgName = DatatypesFactory.eINSTANCE.createON();
        orgName.addText("Good Health Hospital");
        organization.getNames().add(orgName);

        assignedAuthor.setRepresentedOrganization(organization);
        author.setAssignedAuthor(assignedAuthor);
        document.getAuthors().add(author);
    }

    private void addC2Custodian(ClinicalDocument document, List<PatientMeasureData> patients, String clinicId, String clinicName) {
        Custodian custodian = CDAFactory.eINSTANCE.createCustodian();
        AssignedCustodian assignedCustodian = CDAFactory.eINSTANCE.createAssignedCustodian();
        CustodianOrganization organization = CDAFactory.eINSTANCE.createCustodianOrganization();

        II custodianId = DatatypesFactory.eINSTANCE.createII();
        custodianId.setRoot("2.16.840.1.113883.4.336");
        custodianId.setExtension("800890");
        organization.getIds().add(custodianId);

        ON orgName = DatatypesFactory.eINSTANCE.createON();
        orgName.addText("TestSystem Test Deck");
        organization.setName(orgName);

        TEL telecom = DatatypesFactory.eINSTANCE.createTEL();
        telecom.getUses().add(TelecommunicationAddressUse.WP);
        telecom.setValue("tel:(781)271-3000");
        organization.setTelecom(telecom);

        AD address = DatatypesFactory.eINSTANCE.createAD();
        address.addStreetAddressLine("202 Burlington Rd.");
        address.addCity("Bedford");
        address.addState("MA");
        address.addPostalCode("01730");
        address.addCountry("US");
        organization.setAddr(address);

        assignedCustodian.setRepresentedCustodianOrganization(organization);
        custodian.setAssignedCustodian(assignedCustodian);
        document.setCustodian(custodian);
    }

    private void addC2InformationRecipient(ClinicalDocument document) {
        InformationRecipient informationRecipient = CDAFactory.eINSTANCE.createInformationRecipient();
        IntendedRecipient intendedRecipient = CDAFactory.eINSTANCE.createIntendedRecipient();

        II recipientId = DatatypesFactory.eINSTANCE.createII();
        recipientId.setRoot("2.16.840.1.113883.3.249.7");
        recipientId.setExtension("MIPS_INDIV");
        intendedRecipient.getIds().add(recipientId);

        informationRecipient.setIntendedRecipient(intendedRecipient);
        document.getInformationRecipients().add(informationRecipient);
    }

    private void addC2LegalAuthenticator(ClinicalDocument document, String clinicId) {
        LegalAuthenticator authenticator = CDAFactory.eINSTANCE.createLegalAuthenticator();
        authenticator.setTime(DatatypesFactory.eINSTANCE.createTS("20180524170839"));

        CS signatureCode = DatatypesFactory.eINSTANCE.createCS();
        signatureCode.setCode("S");
        authenticator.setSignatureCode(signatureCode);

        AssignedEntity assignedEntity = CDAFactory.eINSTANCE.createAssignedEntity();

        II entityId = DatatypesFactory.eINSTANCE.createII();
        entityId.setRoot("bc01a5d1-3a34-4286-82cc-43eb04c972a7");
        assignedEntity.getIds().add(entityId);

        AD address = DatatypesFactory.eINSTANCE.createAD();
        address.addStreetAddressLine("202 Burlington Rd.");
        address.addCity("Bedford");
        address.addState("MA");
        address.addPostalCode("01730");
        address.addCountry("US");
        assignedEntity.getAddrs().add(address);

        TEL telecom = DatatypesFactory.eINSTANCE.createTEL();
        telecom.getUses().add(TelecommunicationAddressUse.WP);
        telecom.setValue("tel:(781)271-3000");
        assignedEntity.getTelecoms().add(telecom);

        // Person with given and family name
        Person person = CDAFactory.eINSTANCE.createPerson();
        PN name = DatatypesFactory.eINSTANCE.createPN();
        name.addGiven("Henry");
        name.addFamily("Seven");
        person.getNames().add(name);
        assignedEntity.setAssignedPerson(person);

        Organization organization = CDAFactory.eINSTANCE.createOrganization();
        II orgId = DatatypesFactory.eINSTANCE.createII();
        orgId.setRoot("2.16.840.1.113883.19.5");
        organization.getIds().add(orgId);
        ON orgName = DatatypesFactory.eINSTANCE.createON();
        orgName.addText("TestSystem");
        organization.getNames().add(orgName);
        assignedEntity.getRepresentedOrganizations().add(organization);

        authenticator.setAssignedEntity(assignedEntity);
        document.setLegalAuthenticator(authenticator);
    }

    private void addC2Participant(ClinicalDocument document) {
        Participant1 participant = CDAFactory.eINSTANCE.createParticipant1();
        participant.setTypeCode(ParticipationType.DEV);

        AssociatedEntity associatedEntity = CDAFactory.eINSTANCE.createAssociatedEntity();
        associatedEntity.setClassCode(RoleClassAssociative.RGPR);

        II certId = DatatypesFactory.eINSTANCE.createII();
        certId.setRoot("2.16.840.1.113883.3.2074.1");
        certId.setExtension("0015CPV4ZTB4WBU");
        associatedEntity.getIds().add(certId);

        CE code = DatatypesFactory.eINSTANCE.createCE();
        code.setCode("129465004");
        code.setCodeSystem("2.16.840.1.113883.6.96");
        code.setCodeSystemName("SNOMED-CT");
        code.setDisplayName("medical record, device");
        associatedEntity.setCode(code);

        participant.setAssociatedEntity(associatedEntity);
        document.getParticipants().add(participant);
    }

    private void addC2DocumentationOf(ClinicalDocument document, DoctorDetailsData doctorDetails) {
        DocumentationOf documentationOf = CDAFactory.eINSTANCE.createDocumentationOf();
        documentationOf.setTypeCode(ActRelationshipType.DOC);

        ServiceEvent serviceEvent = CDAFactory.eINSTANCE.createServiceEvent();
        serviceEvent.setClassCode(ActClassRoot.PCPR);

        IVL_TS effectiveTime = DatatypesFactory.eINSTANCE.createIVL_TS();
        IVXB_TS low = DatatypesFactory.eINSTANCE.createIVXB_TS();
        low.setNullFlavor(NullFlavor.UNK);
        IVXB_TS high = DatatypesFactory.eINSTANCE.createIVXB_TS();
        high.setNullFlavor(NullFlavor.UNK);
        effectiveTime.setLow(low);
        effectiveTime.setHigh(high);
        serviceEvent.setEffectiveTime(effectiveTime);

        Performer1 performer = CDAFactory.eINSTANCE.createPerformer1();
        performer.setTypeCode(x_ServiceEventPerformer.PRF);

        IVL_TS performerTime = DatatypesFactory.eINSTANCE.createIVL_TS();
        IVXB_TS performerLow = DatatypesFactory.eINSTANCE.createIVXB_TS();
        performerLow.setNullFlavor(NullFlavor.UNK);
        IVXB_TS performerHigh = DatatypesFactory.eINSTANCE.createIVXB_TS();
        performerHigh.setNullFlavor(NullFlavor.UNK);
        performerTime.setLow(performerLow);
        performerTime.setHigh(performerHigh);
        performer.setTime(performerTime);

        AssignedEntity assignedEntity = CDAFactory.eINSTANCE.createAssignedEntity();

        // Use NPI from doctor details
        II npiId = DatatypesFactory.eINSTANCE.createII();
        npiId.setRoot("2.16.840.1.113883.4.6");
        if (doctorDetails != null && StringUtils.hasText(doctorDetails.getNpi())) {
            npiId.setExtension(doctorDetails.getNpi());
        } else {
            npiId.setExtension("12345"); // Default fallback
        }
        assignedEntity.getIds().add(npiId);

        // Use CMS certificate number if available
        II ccnId = DatatypesFactory.eINSTANCE.createII();
        ccnId.setRoot("2.16.840.1.113883.4.336");
        if (doctorDetails != null && StringUtils.hasText(doctorDetails.getCms_certificate_number())) {
            ccnId.setExtension(doctorDetails.getCms_certificate_number());
        } else {
            ccnId.setExtension("12345"); // Default fallback
        }
        assignedEntity.getIds().add(ccnId);

        // Use taxonomy code from doctor details
        CE providerCode = DatatypesFactory.eINSTANCE.createCE();
        if (doctorDetails != null && StringUtils.hasText(doctorDetails.getTaxonomy_code())) {
            providerCode.setCode(doctorDetails.getTaxonomy_code());
        } else {
            providerCode.setCode("207Q00000X"); // Default fallback
        }
        providerCode.setCodeSystem("2.16.840.1.113883.6.101");
        providerCode.setCodeSystemName("Healthcare Provider Taxonomy (HIPAA)");
        assignedEntity.setCode(providerCode);

        // Use address from doctor details
        AD addr = DatatypesFactory.eINSTANCE.createAD();
        addr.getUses().add(PostalAddressUse.HP);
        if (doctorDetails != null && doctorDetails.getResidential_address() != null) {
            DoctorAddress docAddress = doctorDetails.getResidential_address();
            if (StringUtils.hasText(docAddress.getLine1())) {
                addr.addStreetAddressLine(docAddress.getLine1());
            }
            if (StringUtils.hasText(docAddress.getCity())) {
                addr.addCity(docAddress.getCity());
            }
            if (StringUtils.hasText(docAddress.getState())) {
                addr.addState(docAddress.getState());
            }
            if (StringUtils.hasText(docAddress.getPostal_code())) {
                addr.addPostalCode(docAddress.getPostal_code());
            }
            if (StringUtils.hasText(docAddress.getCountry())) {
                addr.addCountry(docAddress.getCountry());
            } else {
                addr.addCountry("US");
            }
        } else {
            // Default fallback
            addr.addStreetAddressLine("13877 Bernier Drive Gateway");
            addr.addCity("Teishaview");
            addr.addState("KS");
            addr.addPostalCode("66653");
            addr.addCountry("US");
        }
        assignedEntity.getAddrs().add(addr);

        // Use doctor name from doctor details
        Person person = CDAFactory.eINSTANCE.createPerson();
        PN name = DatatypesFactory.eINSTANCE.createPN();
        if (doctorDetails != null) {
            if (StringUtils.hasText(doctorDetails.getFirst_name())) {
                name.addGiven(doctorDetails.getFirst_name());
            }
            if (StringUtils.hasText(doctorDetails.getMiddle_name())) {
                name.addGiven(doctorDetails.getMiddle_name());
            }
            if (StringUtils.hasText(doctorDetails.getLast_name())) {
                name.addFamily(doctorDetails.getLast_name());
            }
        }
        // Fallback if no name available
        if (name.getGivens().isEmpty() && name.getFamilies().isEmpty()) {
            name.addGiven("Patsy");
            name.addFamily("Lynch");
        }
        person.getNames().add(name);
        assignedEntity.setAssignedPerson(person);

        // Use organization/clinic details
        Organization org = CDAFactory.eINSTANCE.createOrganization();
        II orgId = DatatypesFactory.eINSTANCE.createII();
        orgId.setRoot("2.16.840.1.113883.4.2");
        if (doctorDetails != null && StringUtils.hasText(doctorDetails.getTax_id_number())) {
            orgId.setExtension(doctorDetails.getTax_id_number());
        } else {
            orgId.setExtension("24362362"); // Default fallback
        }
        org.getIds().add(orgId);

        // Use same address for organization
        AD orgAddr = DatatypesFactory.eINSTANCE.createAD();
        orgAddr.getUses().add(PostalAddressUse.HP);
        if (doctorDetails != null && doctorDetails.getResidential_address() != null) {
            DoctorAddress docAddress = doctorDetails.getResidential_address();
            if (StringUtils.hasText(docAddress.getLine1())) {
                orgAddr.addStreetAddressLine(docAddress.getLine1());
            }
            if (StringUtils.hasText(docAddress.getCity())) {
                orgAddr.addCity(docAddress.getCity());
            }
            if (StringUtils.hasText(docAddress.getState())) {
                orgAddr.addState(docAddress.getState());
            }
            if (StringUtils.hasText(docAddress.getPostal_code())) {
                orgAddr.addPostalCode(docAddress.getPostal_code());
            }
            if (StringUtils.hasText(docAddress.getCountry())) {
                orgAddr.addCountry(docAddress.getCountry());
            } else {
                orgAddr.addCountry("US");
            }
        } else {
            // Default fallback
            orgAddr.addStreetAddressLine("13877 Bernier Drive Gateway");
            orgAddr.addCity("Teishaview");
            orgAddr.addState("KS");
            orgAddr.addPostalCode("66653");
            orgAddr.addCountry("US");
        }
        org.getAddrs().add(orgAddr);

        assignedEntity.getRepresentedOrganizations().add(org);
        performer.setAssignedEntity(assignedEntity);
        serviceEvent.getPerformers().add(performer);
        documentationOf.setServiceEvent(serviceEvent);
        document.getDocumentationOfs().add(documentationOf);
    }

    private void addC2RecordTargets(ClinicalDocument document, List<PatientMeasureData> patients) {
        RecordTarget recordTarget = CDAFactory.eINSTANCE.createRecordTarget();
        PatientRole patientRole = CDAFactory.eINSTANCE.createPatientRole();

        II patientId = DatatypesFactory.eINSTANCE.createII();
        patientId.setNullFlavor(NullFlavor.NA);
        patientRole.getIds().add(patientId);

        recordTarget.setPatientRole(patientRole);
        document.getRecordTargets().add(recordTarget);
    }

    private void addC2ReportingParametersSection(ClinicalDocument document) {
        Component2 component = CDAFactory.eINSTANCE.createComponent2();
        StructuredBody structuredBody = CDAFactory.eINSTANCE.createStructuredBody();
        
        Component3 measureComponent = CDAFactory.eINSTANCE.createComponent3();
        Section measureSection = CDAFactory.eINSTANCE.createSection();
        
        II templateId1 = DatatypesFactory.eINSTANCE.createII();
        templateId1.setRoot("2.16.840.1.113883.10.20.24.2.2");
        measureSection.getTemplateIds().add(templateId1);

        II templateId2 = DatatypesFactory.eINSTANCE.createII();
        templateId2.setRoot("2.16.840.1.113883.10.20.27.2.1");
        templateId2.setExtension("2020-12-01");
        measureSection.getTemplateIds().add(templateId2);

        II templateId3 = DatatypesFactory.eINSTANCE.createII();
        templateId3.setRoot("2.16.840.1.113883.10.20.27.2.3");
        templateId3.setExtension("2022-05-01"); // QRDA Category III Measure Section - CMS (V5)
        measureSection.getTemplateIds().add(templateId3);

        CE sectionCode = DatatypesFactory.eINSTANCE.createCE();
        sectionCode.setCode("55186-1");
        sectionCode.setCodeSystem("2.16.840.1.113883.6.1");
        measureSection.setCode(sectionCode);

        ST sectionTitle = DatatypesFactory.eINSTANCE.createST();
        sectionTitle.addText("Measure Section");
        measureSection.setTitle(sectionTitle);

        StrucDocText sectionText = CDAFactory.eINSTANCE.createStrucDocText();
        StringBuilder tableHtml = new StringBuilder();
        tableHtml.append("<table border=\"1\" width=\"100%\">\n");
        tableHtml.append("  <thead>\n");
        tableHtml.append("    <tr>\n");
        tableHtml.append("      <th>eMeasure Title</th>\n");
        tableHtml.append("      <th>Version specific identifier</th>\n");
        tableHtml.append("    </tr>\n");
        tableHtml.append("  </thead>\n");
        tableHtml.append("  <tbody>\n");
        // Extract measure ID from patients if available, otherwise use default
        String measureId = "8A6D0454-8DF0-2D9F-018E-1434289012A6"; // Default fallback
        tableHtml.append("    <tr>\n");
        tableHtml.append("      <td>Percentage of patients 65 years of age and older who were screened for future fall risk during the measurement period</td>\n");
        tableHtml.append("      <td>").append(measureId).append("</td>\n");
        tableHtml.append("      <td/>\n");
        tableHtml.append("    </tr>\n");
        tableHtml.append("  </tbody>\n");
        tableHtml.append("</table>");
        sectionText.addText(tableHtml.toString());
        measureSection.setText(sectionText);

        measureComponent.setSection(measureSection);
        structuredBody.getComponents().add(measureComponent);
        component.setStructuredBody(structuredBody);
        document.setComponent(component);
    }

    private void addC2MeasureSection(ClinicalDocument document, List<PatientMeasureData> patients, String measurementPeriodStart, String measurementPeriodEnd) {
        StructuredBody structuredBody = document.getComponent().getStructuredBody();
        Section measureSection = structuredBody.getComponents().get(0).getSection();

        long ipop = patients.stream().filter(PatientMeasureData::isInInitialPopulation).count();
        long denex = patients.stream().filter(p -> p.isInInitialPopulation() && p.isDenominatorExcluded()).count();
        long denom = patients.stream().filter(PatientMeasureData::isC2Denominator).count();
        long numer = patients.stream().filter(PatientMeasureData::isC2Numerator).count();

        Entry measureEntry = CDAFactory.eINSTANCE.createEntry();
        Organizer measureOrganizer = CDAFactory.eINSTANCE.createOrganizer();
        measureOrganizer.setClassCode(x_ActClassDocumentEntryOrganizer.CLUSTER);
        measureOrganizer.setMoodCode(ActMood.EVN);

        II templateId1 = DatatypesFactory.eINSTANCE.createII();
        templateId1.setRoot("2.16.840.1.113883.10.20.24.3.98");
        measureOrganizer.getTemplateIds().add(templateId1);

        II templateId2 = DatatypesFactory.eINSTANCE.createII();
        templateId2.setRoot("2.16.840.1.113883.10.20.27.3.1");
        templateId2.setExtension("2020-12-01");
        measureOrganizer.getTemplateIds().add(templateId2);

        II templateId3 = DatatypesFactory.eINSTANCE.createII();
        templateId3.setRoot("2.16.840.1.113883.10.20.27.3.17");
        templateId3.setExtension("2022-05-01"); // Measure Reference and Results template - CMS (V5)
        measureOrganizer.getTemplateIds().add(templateId3);

        II organizerId = DatatypesFactory.eINSTANCE.createII();
        organizerId.setRoot("1.3.6.1.4.1.115");
        organizerId.setExtension(UUID.randomUUID().toString());
        measureOrganizer.getIds().add(organizerId);

        CS statusCode = DatatypesFactory.eINSTANCE.createCS();
        statusCode.setCode("completed");
        measureOrganizer.setStatusCode(statusCode);

        // Measure reference (external document)
        Reference measureReference = CDAFactory.eINSTANCE.createReference();
        measureReference.setTypeCode(x_ActRelationshipExternalReference.REFR);

        ExternalDocument externalDoc = CDAFactory.eINSTANCE.createExternalDocument();
        externalDoc.setClassCode(ActClassDocument.DOC);
        externalDoc.setMoodCode(ActMood.EVN);

        String extractedMeasureId ="8A6D0454-8DF0-2D9F-018E-1434289012A6"; // Default fallback

        String extractedMeasureName = patients.stream()
                .filter(p -> StringUtils.hasText(p.getMeasureName()))
                .map(PatientMeasureData::getMeasureName)
                .findFirst()
                .orElse("Percentage of patients 65 years of age and older who were screened for future fall risk during the measurement period");
        

        II measureId = DatatypesFactory.eINSTANCE.createII();
        measureId.setRoot("2.16.840.1.113883.4.738");
        measureId.setExtension(extractedMeasureId);
        externalDoc.getIds().add(measureId);

        ST measureText = DatatypesFactory.eINSTANCE.createST();
        measureText.addText(extractedMeasureName);
        externalDoc.setText(measureText);

        // setId is the eMeasure version neutral id
        II setId = DatatypesFactory.eINSTANCE.createII();
        setId.setRoot("BC5B4A57-B964-4399-9D40-667C896F31EA");
        externalDoc.setSetId(setId);

        measureReference.setExternalDocument(externalDoc);
        measureOrganizer.getReferences().add(measureReference);

        // Prepare lists
        List<PatientMeasureData> ipopPatients = patients.stream().filter(PatientMeasureData::isInInitialPopulation).collect(Collectors.toList());
        List<PatientMeasureData> denexPatients = patients.stream().filter(p -> p.isInInitialPopulation() && p.isDenominatorExcluded()).collect(Collectors.toList());
        List<PatientMeasureData> denomPatients = patients.stream().filter(PatientMeasureData::isC2Denominator).collect(Collectors.toList());
        List<PatientMeasureData> numerPatients = patients.stream().filter(PatientMeasureData::isC2Numerator).collect(Collectors.toList());

        measureOrganizer.getComponents().add(createPopulationObservation("IPOP", ipop, ipopPatients, "2EFD85A8-0F6A-4C9D-B500-B13559B6E000"));
        measureOrganizer.getComponents().add(createPopulationObservation("DENOM", denom, denomPatients, "45522BD1-875C-4C6D-BC3E-8CE25CA84D36"));
        measureOrganizer.getComponents().add(createPopulationObservation("NUMER", numer, numerPatients, "AF945143-9A66-47D6-819A-7C8463EF7E30"));
        measureOrganizer.getComponents().add(createPopulationObservation("DENEX", denex, denexPatients, "B58EC200-EE42-4105-A721-EDAFBFC7311C"));

        measureEntry.setOrganizer(measureOrganizer);
        measureSection.getEntries().add(measureEntry);

        Entry reportingParamsEntry = CDAFactory.eINSTANCE.createEntry();
        
        Act reportingParamsAct = CDAFactory.eINSTANCE.createAct();
        reportingParamsAct.setClassCode(x_ActClassDocumentEntryAct.ACT);
        reportingParamsAct.setMoodCode(x_DocumentActMood.EVN);

        II reportingParamsTemplateId = DatatypesFactory.eINSTANCE.createII();
        reportingParamsTemplateId.setRoot("2.16.840.1.113883.10.20.17.3.8");
        reportingParamsTemplateId.setExtension("2020-12-01");
        reportingParamsAct.getTemplateIds().add(reportingParamsTemplateId);

        II reportingParamsId = DatatypesFactory.eINSTANCE.createII();
        reportingParamsId.setRoot("1.3.6.1.4.1.115");
        reportingParamsId.setExtension(UUID.randomUUID().toString());
        reportingParamsAct.getIds().add(reportingParamsId);

        CE reportingParamsCode = DatatypesFactory.eINSTANCE.createCE();
        reportingParamsCode.setCode("252116004");
        reportingParamsCode.setCodeSystem("2.16.840.1.113883.6.96");
        reportingParamsCode.setDisplayName("Observation Parameters");
        reportingParamsAct.setCode(reportingParamsCode);

        IVL_TS effectiveTime = DatatypesFactory.eINSTANCE.createIVL_TS();
        IVXB_TS low = DatatypesFactory.eINSTANCE.createIVXB_TS();
        // Convert yyyy-MM-dd to yyyyMMddHHmmss format (start of day)
        String startDateFormatted = convertDateToQrdaFormat(measurementPeriodStart, true);
        low.setValue(startDateFormatted);
        IVXB_TS high = DatatypesFactory.eINSTANCE.createIVXB_TS();
        // Convert yyyy-MM-dd to yyyyMMddHHmmss format (end of day)
        String endDateFormatted = convertDateToQrdaFormat(measurementPeriodEnd, false);
        high.setValue(endDateFormatted);
        effectiveTime.setLow(low);
        effectiveTime.setHigh(high);
        reportingParamsAct.setEffectiveTime(effectiveTime);

        reportingParamsEntry.setAct(reportingParamsAct);
        measureSection.getEntries().add(reportingParamsEntry);

    }

    private Component4 createPopulationObservation(String populationType, long count, List<PatientMeasureData> populationPatients, String referenceId) {
        Component4 component = CDAFactory.eINSTANCE.createComponent4();

        Observation observation = CDAFactory.eINSTANCE.createObservation();
        observation.setClassCode(ActClassObservation.OBS);
        observation.setMoodCode(x_ActMoodDocumentObservation.EVN);

        II templateId1 = DatatypesFactory.eINSTANCE.createII();
        templateId1.setRoot("2.16.840.1.113883.10.20.27.3.5");
        templateId1.setExtension("2016-09-01");
        observation.getTemplateIds().add(templateId1);

        II templateId2 = DatatypesFactory.eINSTANCE.createII();
        templateId2.setRoot("2.16.840.1.113883.10.20.27.3.16");
        templateId2.setExtension("2019-05-01"); // Measure Data - CMS (V4)
        observation.getTemplateIds().add(templateId2);

        CE obsCode = DatatypesFactory.eINSTANCE.createCE();
        obsCode.setCode("ASSERTION");
        obsCode.setCodeSystem("2.16.840.1.113883.5.4");
        obsCode.setDisplayName("Assertion");
        obsCode.setCodeSystemName("ActCode");
        observation.setCode(obsCode);

        CS statusCode = DatatypesFactory.eINSTANCE.createCS();
        statusCode.setCode("completed");
        observation.setStatusCode(statusCode);

        CD populationValue = DatatypesFactory.eINSTANCE.createCD();
        populationValue.setCode(populationType);
        populationValue.setCodeSystem("2.16.840.1.113883.5.4");
        populationValue.setCodeSystemName("ActCode");
        observation.getValues().add(populationValue);

        EntryRelationship countEntryRel = CDAFactory.eINSTANCE.createEntryRelationship();
        countEntryRel.setTypeCode(x_ActRelationshipEntryRelationship.SUBJ);
        countEntryRel.setInversionInd(true);
        Observation countObs = createCountObservation(count);
        countEntryRel.setObservation(countObs);
        observation.getEntryRelationships().add(countEntryRel);

        addSDEObservationsToPopulation(observation, populationPatients, populationType);

        Reference popReference = CDAFactory.eINSTANCE.createReference();
        popReference.setTypeCode(x_ActRelationshipExternalReference.REFR);
        ExternalObservation popExternalObs = CDAFactory.eINSTANCE.createExternalObservation();
        popExternalObs.setClassCode(ActClassObservation.OBS);
        popExternalObs.setMoodCode(ActMood.EVN);
        II popExtId = DatatypesFactory.eINSTANCE.createII();
        popExtId.setRoot(referenceId);
        popExternalObs.getIds().add(popExtId);
        popReference.setExternalObservation(popExternalObs);
        observation.getReferences().add(popReference);

        component.setObservation(observation);
        return component;
    }

    private void addSDEObservationsToPopulation(Observation populationObs, List<PatientMeasureData> populationPatients, String populationType) {
        List<PatientMeasureData> pts = populationPatients != null ? populationPatients : Collections.emptyList();
        
        Map<String, Map<String, Long>> sdeCounts = calculateSDECounts(pts);
        
        log.debug("Adding SDEs for population {} with {} patients", populationType, pts.size());

        // RACE: Include all race codes in order: 1002-5, 2028-9, 2054-5, 2106-3, 2076-8, 2131-1
        String[] raceCodes = { "1002-5", "2028-9", "2054-5", "2106-3", "2076-8", "2131-1" };
        Map<String, Long> raceMap = sdeCounts.getOrDefault("RACE", Collections.emptyMap());
        for (String rc : raceCodes) {
            long cnt = raceMap.getOrDefault(rc, 0L);
            // Include all race codes even if count is 0
            populationObs.getEntryRelationships().add(createRaceSDEObservation(rc, cnt, pts));
        }

        String[] ethCodes = { "2186-5", "2135-2" };
        Map<String, Long> ethMap = sdeCounts.getOrDefault("ETHNICITY", Collections.emptyMap());
        for (String ec : ethCodes) {
            long cnt = ethMap.getOrDefault(ec, 0L);
            // Include all ethnicity codes even if count is 0
            populationObs.getEntryRelationships().add(createEthnicitySDEObservation(ec, cnt, pts));
        }

        // SEX: Include both codes in order: M, F
        Map<String, Long> sexMap = sdeCounts.getOrDefault("SEX", Collections.emptyMap());
        long maleCount = sexMap.getOrDefault("M", 0L);
        long femaleCount = sexMap.getOrDefault("F", 0L);
        // Include both sex codes even if count is 0 - order: M, F
        populationObs.getEntryRelationships().add(createSexSDEObservation("M", maleCount));
        populationObs.getEntryRelationships().add(createSexSDEObservation("F", femaleCount));

        // PAYER: Include all payer codes in order: A, D, B, C
        Map<String, Long> payerMap = sdeCounts.getOrDefault("PAYER", Collections.emptyMap());
        long medicareCount = payerMap.getOrDefault("A", 0L); // Medicare
        long otherCount = payerMap.getOrDefault("D", 0L);    // Other
        long medicaidCount = payerMap.getOrDefault("B", 0L); // Medicaid
        long tricareCount = payerMap.getOrDefault("C", 0L);  // TRICARE

        log.debug("Payer counts for population {}: Medicare (A)={}, Medicaid (B)={}, Other (D)={}, TRICARE (C)={}",
                populationType, medicareCount, medicaidCount, otherCount, tricareCount);

        // Include all payer codes in order: A, D, B, C (even if count is 0)
        populationObs.getEntryRelationships().add(createPayerSDEObservation("A", "Medicare", medicareCount, pts));
        populationObs.getEntryRelationships().add(createPayerSDEObservation("D", "Other", otherCount, pts));
        populationObs.getEntryRelationships().add(createPayerSDEObservation("B", "Medicaid", medicaidCount, pts));
        populationObs.getEntryRelationships().add(createPayerSDEObservation("C", "TRICARE", tricareCount, pts));

        log.debug("Added SDEs for population {}: {} entryRelationships total",
                populationType, populationObs.getEntryRelationships().size());
    }

    private EntryRelationship createRaceSDEObservation(String code, long count, List<PatientMeasureData> patients) {
        Observation sdeObs = CDAFactory.eINSTANCE.createObservation();
        sdeObs.setClassCode(ActClassObservation.OBS);
        sdeObs.setMoodCode(x_ActMoodDocumentObservation.EVN);

        II tid = DatatypesFactory.eINSTANCE.createII();
        tid.setRoot("2.16.840.1.113883.10.20.27.3.8");
        tid.setExtension("2016-09-01");
        sdeObs.getTemplateIds().add(tid);

        II id = DatatypesFactory.eINSTANCE.createII();
        id.setNullFlavor(NullFlavor.NA);
        sdeObs.getIds().add(id);

        // Code - standard format doesn't include codeSystemName or displayName
        CE codeCe = DatatypesFactory.eINSTANCE.createCE();
        codeCe.setCode("72826-1");
        codeCe.setCodeSystem("2.16.840.1.113883.6.1");
        sdeObs.setCode(codeCe);

        sdeObs.setStatusCode(DatatypesFactory.eINSTANCE.createCS());
        sdeObs.getStatusCode().setCode("completed");

        CD valueCd = DatatypesFactory.eINSTANCE.createCD();
        valueCd.setCode(code);
        valueCd.setCodeSystem("2.16.840.1.113883.6.238");
        sdeObs.getValues().add(valueCd);

        EntryRelationship countRel = CDAFactory.eINSTANCE.createEntryRelationship();
        countRel.setTypeCode(x_ActRelationshipEntryRelationship.SUBJ);
        countRel.setInversionInd(true);
        countRel.setObservation(createCountObservation(count));
        sdeObs.getEntryRelationships().add(countRel);

        EntryRelationship compRel = CDAFactory.eINSTANCE.createEntryRelationship();
        compRel.setTypeCode(x_ActRelationshipEntryRelationship.COMP);
        compRel.setObservation(sdeObs);
        return compRel;
    }

    private EntryRelationship createEthnicitySDEObservation(String code, long count, List<PatientMeasureData> patients) {
        Observation sdeObs = CDAFactory.eINSTANCE.createObservation();
        sdeObs.setClassCode(ActClassObservation.OBS);
        sdeObs.setMoodCode(x_ActMoodDocumentObservation.EVN);

        II tid = DatatypesFactory.eINSTANCE.createII();
        tid.setRoot("2.16.840.1.113883.10.20.27.3.7");
        tid.setExtension("2016-09-01");
        sdeObs.getTemplateIds().add(tid);

        II id = DatatypesFactory.eINSTANCE.createII();
        id.setNullFlavor(NullFlavor.NA);
        sdeObs.getIds().add(id);

        CE codeCe = DatatypesFactory.eINSTANCE.createCE();
        codeCe.setCode("69490-1");
        codeCe.setCodeSystem("2.16.840.1.113883.6.1");
        sdeObs.setCode(codeCe);

        sdeObs.setStatusCode(DatatypesFactory.eINSTANCE.createCS());
        sdeObs.getStatusCode().setCode("completed");

        CD valueCd = DatatypesFactory.eINSTANCE.createCD();
        valueCd.setCode(code);
        valueCd.setCodeSystem("2.16.840.1.113883.6.238");
        sdeObs.getValues().add(valueCd);

        EntryRelationship countRel = CDAFactory.eINSTANCE.createEntryRelationship();
        countRel.setTypeCode(x_ActRelationshipEntryRelationship.SUBJ);
        countRel.setInversionInd(true);
        countRel.setObservation(createCountObservation(count));
        sdeObs.getEntryRelationships().add(countRel);

        EntryRelationship compRel = CDAFactory.eINSTANCE.createEntryRelationship();
        compRel.setTypeCode(x_ActRelationshipEntryRelationship.COMP);
        compRel.setObservation(sdeObs);
        return compRel;
    }

    private EntryRelationship createSexSDEObservation(String sexCode, long count) {
        Observation sdeObs = CDAFactory.eINSTANCE.createObservation();
        sdeObs.setClassCode(ActClassObservation.OBS);
        sdeObs.setMoodCode(x_ActMoodDocumentObservation.EVN);

        II tid = DatatypesFactory.eINSTANCE.createII();
        tid.setRoot("2.16.840.1.113883.10.20.27.3.6");
        tid.setExtension("2016-09-01");
        sdeObs.getTemplateIds().add(tid);

        II id = DatatypesFactory.eINSTANCE.createII();
        id.setNullFlavor(NullFlavor.NA);
        sdeObs.getIds().add(id);

        CE codeCe = DatatypesFactory.eINSTANCE.createCE();
        codeCe.setCode("76689-9");
        codeCe.setCodeSystem("2.16.840.1.113883.6.1");
        sdeObs.setCode(codeCe);

        sdeObs.setStatusCode(DatatypesFactory.eINSTANCE.createCS());
        sdeObs.getStatusCode().setCode("completed");

        CD valueCd = DatatypesFactory.eINSTANCE.createCD();
        valueCd.setCode(sexCode); // Direct code: M or F
        valueCd.setCodeSystem("2.16.840.1.113883.5.1");
        sdeObs.getValues().add(valueCd);

        EntryRelationship countRel = CDAFactory.eINSTANCE.createEntryRelationship();
        countRel.setTypeCode(x_ActRelationshipEntryRelationship.SUBJ);
        countRel.setInversionInd(true);
        countRel.setObservation(createCountObservation(count));
        sdeObs.getEntryRelationships().add(countRel);

        EntryRelationship compRel = CDAFactory.eINSTANCE.createEntryRelationship();
        compRel.setTypeCode(x_ActRelationshipEntryRelationship.COMP);
        compRel.setObservation(sdeObs);
        return compRel;
    }

    private EntryRelationship createPayerSDEObservation(String code, String displayName, long count, List<PatientMeasureData> patients) {
        Observation sdeObs = CDAFactory.eINSTANCE.createObservation();
        sdeObs.setClassCode(ActClassObservation.OBS);
        sdeObs.setMoodCode(x_ActMoodDocumentObservation.EVN);

        // Payer SDE template IDs - standard format has two templateIds
        II tid1 = DatatypesFactory.eINSTANCE.createII();
        tid1.setRoot("2.16.840.1.113883.10.20.27.3.9");
        tid1.setExtension("2016-02-01");
        sdeObs.getTemplateIds().add(tid1);
        
        II tid2 = DatatypesFactory.eINSTANCE.createII();
        tid2.setRoot("2.16.840.1.113883.10.20.27.3.18");
        tid2.setExtension("2018-05-01");
        sdeObs.getTemplateIds().add(tid2);

        II id = DatatypesFactory.eINSTANCE.createII();
        id.setNullFlavor(NullFlavor.NA);
        sdeObs.getIds().add(id);

        CE codeCe = DatatypesFactory.eINSTANCE.createCE();
        codeCe.setCode("48768-6");
        codeCe.setCodeSystem("2.16.840.1.113883.6.1");
        sdeObs.setCode(codeCe);

        sdeObs.setStatusCode(DatatypesFactory.eINSTANCE.createCS());
        sdeObs.getStatusCode().setCode("completed");

        CD valueCd = DatatypesFactory.eINSTANCE.createCD();
        valueCd.setNullFlavor(NullFlavor.OTH);
        
        CD translation = DatatypesFactory.eINSTANCE.createCD();
        translation.setCode(code); // "A" (Medicare), "B" (Medicaid), or "D" (Other) - Standard format uses alphabetic codes
        translation.setCodeSystem("2.16.840.1.113883.3.249.12");
        translation.setCodeSystemName("CMS Clinical Codes");
        valueCd.getTranslations().add(translation);
        
        sdeObs.getValues().add(valueCd);

        EntryRelationship countRel = CDAFactory.eINSTANCE.createEntryRelationship();
        countRel.setTypeCode(x_ActRelationshipEntryRelationship.SUBJ);
        countRel.setInversionInd(true);
        countRel.setObservation(createCountObservation(count));
        sdeObs.getEntryRelationships().add(countRel);

        EntryRelationship compRel = CDAFactory.eINSTANCE.createEntryRelationship();
        compRel.setTypeCode(x_ActRelationshipEntryRelationship.COMP);
        compRel.setObservation(sdeObs);
        return compRel;
    }

    private Map<String, Map<String, Long>> calculateSDECounts(List<PatientMeasureData> patients) {
        Map<String, Map<String, Long>> sde = new HashMap<>();

        Map<String, Long> raceMap = new LinkedHashMap<>();
        raceMap.put("2076-8", 0L); // Native Hawaiian or Other Pacific Islander
        raceMap.put("2106-3", 0L); // White
        raceMap.put("1002-5", 0L); // American Indian or Alaska Native
        raceMap.put("2054-5", 0L); // Black or African American
        raceMap.put("2028-9", 0L); // Asian
        raceMap.put("2131-1", 0L); // Other
        sde.put("RACE", raceMap);

        Map<String, Long> ethMap = new LinkedHashMap<>();
        ethMap.put("2186-5", 0L); // Not Hispanic or Latino
        ethMap.put("2135-2", 0L); // Hispanic or Latino
        sde.put("ETHNICITY", ethMap);

        Map<String, Long> sexMap = new LinkedHashMap<>();
        sexMap.put("F", 0L); // Female
        sexMap.put("M", 0L); // Male
        sde.put("SEX", sexMap);

        Map<String, Long> payerMap = new LinkedHashMap<>();
        payerMap.put("A", 0L); // Medicare
        payerMap.put("B", 0L); // Medicaid
        payerMap.put("D", 0L); // Other (includes Private Health Insurance)
        payerMap.put("C", 0L); // TRICARE
        sde.put("PAYER", payerMap);

        if (patients == null || patients.isEmpty()) {
            return sde;
        }

        log.info("Calculating SDE counts for {} patients", patients.size());
        
        int patientsWithGender = 0;
        int patientsWithEthnicity = 0;
        int patientsWithRace = 0;
        int patientsWithPayer = 0;
        
        for (int i = 0; i < patients.size(); i++) {
            PatientMeasureData p = patients.get(i);
            
            String raceRaw = p.getPersonalDetailsData() != null && p.getPersonalDetailsData().getResponse() != null
                    ? extractRaceFromPersonalDetails(p.getPersonalDetailsData().getResponse()) : null;
            String raceDisplay = normalizeString(raceRaw);
            

            String raceCode = null;
            if (raceRaw != null && raceRaw.contains("2076-8")) {
                raceCode = "2076-8"; // Native Hawaiian or Other Pacific Islander
            } else if (raceRaw != null && raceRaw.contains("2106-3")) {
                raceCode = "2106-3"; // White
            } else if (raceRaw != null && raceRaw.contains("1002-5")) {
                raceCode = "1002-5"; // American Indian or Alaska Native
            } else if (raceRaw != null && raceRaw.contains("2054-5")) {
                raceCode = "2054-5"; // Black or African American
            } else if (raceRaw != null && raceRaw.contains("2028-9")) {
                raceCode = "2028-9"; // Asian
            } else if ("NATIVE HAWAIIAN OR OTHER PACIFIC ISLANDER".equals(raceDisplay) ||
                      (raceRaw != null && (raceRaw.toLowerCase().contains("hawaiian") || raceRaw.toLowerCase().contains("pacific")))) {
                raceCode = "2076-8";
            } else if ("WHITE".equals(raceDisplay) || (raceRaw != null && raceRaw.toLowerCase().contains("white"))) {
                raceCode = "2106-3";
            } else if ("AMERICAN INDIAN OR ALASKA NATIVE".equals(raceDisplay) || "AMERICAN INDIAN".equals(raceDisplay) ||
                      (raceRaw != null && (raceRaw.toLowerCase().contains("indian") || raceRaw.toLowerCase().contains("alaska")))) {
                raceCode = "1002-5";
            } else if ("BLACK OR AFRICAN AMERICAN".equals(raceDisplay) || "BLACK".equals(raceDisplay) ||
                      (raceRaw != null && (raceRaw.toLowerCase().contains("black") || raceRaw.toLowerCase().contains("african")))) {
                raceCode = "2054-5";
            } else if ("ASIAN".equals(raceDisplay) || (raceRaw != null && raceRaw.toLowerCase().contains("asian"))) {
                raceCode = "2028-9";
            } else if (raceRaw != null && !raceRaw.trim().isEmpty()) {
                // Other (code 2131-1)
                raceCode = "2131-1";
            }
            
            if (raceCode != null) {
                raceMap.merge(raceCode, 1L, Long::sum);
                patientsWithRace++;
            }
            String ethRaw = p.getPersonalDetailsData() != null && p.getPersonalDetailsData().getResponse() != null
                    ? extractEthnicityFromPersonalDetails(p.getPersonalDetailsData().getResponse()) : null;
            String eth = normalizeString(ethRaw);
            
            log.debug("Patient {} - raw ethnicity: '{}', normalized: '{}'", i, ethRaw, eth);
            
            String ethCode = null;
            
            if (ethRaw != null && ethRaw.contains("2135-2")) {
                ethCode = "2135-2"; // Hispanic or Latino
            } else if (ethRaw != null && ethRaw.contains("2186-5")) {
                ethCode = "2186-5"; // Not Hispanic or Latino
            }
            else if (eth.contains("NOT HISPANIC") || eth.contains("NON-HISPANIC") || eth.equals("2186-5")) {
                ethCode = "2186-5"; // Not Hispanic or Latino
            }
            else if (eth.contains("HISPANIC") || eth.contains("LATINO") || eth.equals("2135-2")) {
                ethCode = "2135-2"; // Hispanic or Latino
            }
            else if (ethRaw != null && !ethRaw.trim().isEmpty() &&
                     !eth.equals("UNKNOWN") && !eth.equals("DECLINED")) {
                ethCode = "2186-5"; // Default to Not Hispanic or Latino
            }
            
            if (ethCode != null) {
                ethMap.merge(ethCode, 1L, Long::sum);
                patientsWithEthnicity++;
            }

            String genderRaw = null;
            if (p.getPersonalDetailsData() != null && p.getPersonalDetailsData().getResponse() != null) {
                genderRaw = extractGenderFromPersonalDetails(p.getPersonalDetailsData().getResponse());
            }

            String gender = normalizeString(genderRaw);
            
            if (genderRaw != null && (genderRaw.contains("248152002") || genderRaw.contains("248153007"))) {
                if (genderRaw.contains("248152002")) {
                    gender = "M"; // Male SNOMED code
                    log.info("Patient {} - Found Male SNOMED code in gender field", i);
                } else if (genderRaw.contains("248153007")) {
                    gender = "F"; // Female SNOMED code
                    log.info("Patient {} - Found Female SNOMED code in gender field", i);
                }
            }
            

            String sexCode = null;
            if ("F".equals(gender) || "FEMALE".equals(gender) ||
                (genderRaw != null && (genderRaw.equalsIgnoreCase("F") || genderRaw.equalsIgnoreCase("FEMALE") || genderRaw.toLowerCase().contains("female")))) {
                sexCode = "F"; // Female
            }
            else if ("M".equals(gender) || "MALE".equals(gender) ||
                      (genderRaw != null && (genderRaw.equalsIgnoreCase("M") || genderRaw.equalsIgnoreCase("MALE") || genderRaw.toLowerCase().contains("male")))) {
                sexCode = "M"; // Male
            }
            else if (genderRaw != null && !genderRaw.trim().isEmpty()) {
                String genderLower = genderRaw.toLowerCase().trim();
                if (genderLower.startsWith("f") || genderLower.equals("2") || genderLower.contains("248153007")) {
                    sexCode = "F";
                } else if (genderLower.startsWith("m") || genderLower.equals("1") || genderLower.contains("248152002")) {
                    sexCode = "M";
                }
            }
            
            if (sexCode != null) {
                sexMap.merge(sexCode, 1L, Long::sum);
                patientsWithGender++;
            } else {
                if (p.getPersonalDetailsData() != null && p.getPersonalDetailsData().getResponse() != null) {
                } else {
                    log.warn("Patient {} - PersonalDetailsData or Response is null. PersonalDetailsData={}, Response={}", 
                            i, p.getPersonalDetailsData() != null, 
                            p.getPersonalDetailsData() != null && p.getPersonalDetailsData().getResponse() != null);
                }
            }

            String payerRaw = extractPayerFromPatient(p);
            String payer = payerRaw == null ? "" : payerRaw.trim().toUpperCase();

            String payerCode;
            
            if (payer.equals("1") ||
                payer.equals("A") ||
                payer.equals("MEDICARE") ||
                payer.contains("MEDICARE") ||
                payer.equals("MCR") ||
                payer.equals("MCARE") ||
                payer.contains("MEDICARE ADVANTAGE")) {
                payerCode = "A"; // Medicare
            }
            else if (payer.equals("2") ||
                     payer.equals("B") ||
                     payer.equals("MEDICAID") ||
                     payer.contains("MEDICAID") ||
                     payer.equals("MCD")) {
                payerCode = "B"; // Medicaid
            }
            else if (payer.equals("3") ||
                     payer.equals("C") ||
                     payer.equals("TRICARE") ||
                     payer.contains("TRICARE")) {
                payerCode = "C"; // TRICARE
            }
            else if (payer.contains("PRIVATE") ||
                     payer.contains("COMMERCIAL") ||
                     payer.contains("INSURANCE") ||
                     payer.equals("9") ||
                     payer.equals("D") ||
                     payer.equals("OTHER") ||
                     payer.contains("OTHER")) {
                payerCode = "D"; // Other
            }
            else {
                if (payerRaw == null || payerRaw.trim().isEmpty()) {
                    log.warn("Patient {} - No payer information available, defaulting to 'D' (Other)", i);
                } else {
                    log.warn("Patient {} - Unknown payer type '{}', defaulting to 'D' (Other)", i, payerRaw);
                }
                payerCode = "D"; // Other - must count all patients for Cypress validation
            }
            
            payerMap.merge(payerCode, 1L, Long::sum);
            patientsWithPayer++;
        }
        
        log.info("Final SDE counts - SEX: {}, ETHNICITY: {}, RACE: {}, PAYER: {}", 
                sexMap, ethMap, raceMap, payerMap);

        return sde;
    }

    private String normalizeString(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    /**
     * Race and ethnicity are multi-valued on the shared DTO, because a patient may report more
     * than one. CDA carries a single code, so the first populated value is the one used - the
     * same choice the C1 generator makes.
     */
    private String firstValue(List<String> values) {
        if (CollectionUtils.isEmpty(values)) {
            return null;
        }
        return values.stream().filter(StringUtils::hasText).findFirst().orElse(null);
    }

    private String extractRaceFromPersonalDetails(PersonalDetailsResponseBlock response) {
        // Attempt to find patientInformation map first entry and its race field
        if (response == null) return null;
        try {
            Object first = response.getPatientInformation().values().stream().filter(Objects::nonNull).findFirst().orElse(null);
            if (first instanceof PatientInformation) {
                return firstValue(((PatientInformation) first).getRace());
            }
        } catch (Exception e) {
            // fallback null
        }
        return null;
    }

    private String extractEthnicityFromPersonalDetails(PersonalDetailsResponseBlock response) {
        if (response == null) return null;
        try {
            Object first = response.getPatientInformation().values().stream().filter(Objects::nonNull).findFirst().orElse(null);
            if (first instanceof PatientInformation) {
                return firstValue(((PatientInformation) first).getEthnicity());
            }
        } catch (Exception e) {}
        return null;
    }

    private String extractGenderFromPersonalDetails(PersonalDetailsResponseBlock response) {
        if (response == null) {
            return null;
        }
        try {
            if (response.getPatientInformation() == null) {
                return null;
            }
            
            Object first = response.getPatientInformation().values().stream().filter(Objects::nonNull).findFirst().orElse(null);
            if (first instanceof PatientInformation) {
                PatientInformation patientInfo = (PatientInformation) first;
                String gender = patientInfo.getGender();
                return gender;
            } else {
                log.debug("extractGenderFromPersonalDetails: First patient info is not PatientInformation instance: {}", 
                        first != null ? first.getClass().getName() : "null");
            }
        } catch (Exception e) {
            log.warn("extractGenderFromPersonalDetails: Exception extracting gender: {}", e.getMessage(), e);
        }
        return null;
    }

    private String extractPayerFromPatient(PatientMeasureData p) {
        try {
            if (p.getInsuranceDetails() != null && !p.getInsuranceDetails().isEmpty()) {
                InsuranceDetails ins = p.getInsuranceDetails().get(0);
                if (ins != null) {
                    if (ins.getPayer_type() != null && !ins.getPayer_type().trim().isEmpty()) {
                        return ins.getPayer_type();
                    }
                    if (ins.getInsurance_payer() != null && ins.getInsurance_payer().getName() != null) {
                        return ins.getInsurance_payer().getName();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting payer from patient: {}", e.getMessage());
        }
        return null; // Return null instead of empty string to indicate missing data
    }


    private Observation createCountObservation(long count) {
        Observation countObs = CDAFactory.eINSTANCE.createObservation();
        countObs.setClassCode(ActClassObservation.OBS);
        countObs.setMoodCode(x_ActMoodDocumentObservation.EVN);

        II countTid = DatatypesFactory.eINSTANCE.createII();
        countTid.setRoot("2.16.840.1.113883.10.20.27.3.3");
        countObs.getTemplateIds().add(countTid);

        CE countCode = DatatypesFactory.eINSTANCE.createCE();
        countCode.setCode("MSRAGG");
        countCode.setCodeSystem("2.16.840.1.113883.5.4");
        countCode.setCodeSystemName("ActCode");
        countCode.setDisplayName("rate aggregation");
        countObs.setCode(countCode);

        INT countValue = DatatypesFactory.eINSTANCE.createINT();
        countValue.setValue(BigInteger.valueOf(count));
        countObs.getValues().add(countValue);

        CE methodCode = DatatypesFactory.eINSTANCE.createCE();
        methodCode.setCode("COUNT");
        methodCode.setCodeSystem("2.16.840.1.113883.5.84");
        methodCode.setCodeSystemName("ObservationMethod");
        methodCode.setDisplayName("Count");
        countObs.getMethodCodes().add(methodCode);

        return countObs;
    }


    private byte[] serializeQrdaIII(ClinicalDocument document) throws Exception {
        String xml = generateC2Xml(document);
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry entry = new ZipEntry("qrda-iii-summary.xml");
            zos.putNextEntry(entry);
            zos.write(xml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        
        return baos.toByteArray();
    }

    private String generateC2Xml(ClinicalDocument document) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            CDAUtil.save(document, outputStream);
            String xml = outputStream.toString(StandardCharsets.UTF_8);

            if (xml.contains("<ClinicalDocument")) {
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<ClinicalDocument[^>]*>");
                java.util.regex.Matcher matcher = pattern.matcher(xml);
                
                if (matcher.find()) {
                    String originalTag = matcher.group(0);
                    
                    String newTag = "<ClinicalDocument xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns=\"urn:hl7-org:v3\" xmlns:voc=\"urn:hl7-org:v3/voc\">";
                    xml = xml.replaceFirst(java.util.regex.Pattern.quote(originalTag), newTag);
                }
            }

            xml = xml.replaceAll("(<code\\s+)xsi:type=\"CE\"\\s+", "$1");
            xml = xml.replaceAll("(<methodCode\\s+)xsi:type=\"CE\"\\s+", "$1");

            xml = xml.replaceAll(
                "<code\\s+code=\"([^\"]+)\"\\s+codeSystem=\"([^\"]+)\"\\s+codeSystemName=\"([^\"]+)\"\\s+displayName=\"([^\"]+)\"",
                "<code code=\"$1\" codeSystem=\"$2\" displayName=\"$4\" codeSystemName=\"$3\""
            );

            xml = xml.replaceAll(
                "<methodCode\\s+code=\"([^\"]+)\"\\s+codeSystem=\"([^\"]+)\"\\s+codeSystemName=\"([^\"]+)\"\\s+displayName=\"([^\"]+)\"",
                "<methodCode code=\"$1\" displayName=\"$4\" codeSystem=\"$2\" codeSystemName=\"$3\""
            );

            // Add xsi:type="CD" to value elements in population observations
            xml = xml.replaceAll(
                "<value\\s+code=\"([^\"]+)\"\\s+codeSystem=\"([^\"]+)\"",
                "<value xsi:type=\"CD\" code=\"$1\" codeSystem=\"$2\""
            );

            // Add xsi:type="CD" to value elements with codeSystemName
            xml = xml.replaceAll(
                "<value\\s+code=\"([^\"]+)\"\\s+codeSystem=\"([^\"]+)\"\\s+codeSystemName=\"([^\"]+)\"",
                "<value xsi:type=\"CD\" code=\"$1\" codeSystem=\"$2\" codeSystemName=\"$3\""
            );

            // Add xsi:type="INT" to value elements with value attribute (count observations)
            xml = xml.replaceAll(
                "<value\\s+value=\"([^\"]+)\"",
                "<value xsi:type=\"INT\" value=\"$1\""
            );

            // Handle value elements with nullFlavor and translation (payer SDE and sex SDE)
            xml = xml.replaceAll(
                "<value\\s+nullFlavor=\"([^\"]+)\"",
                "<value xsi:type=\"CD\" nullFlavor=\"$1\""
            );
            
            // Ensure translation elements in sex SDE have codeSystemName attribute
            xml = xml.replaceAll(
                "<translation\\s+code=\"([^\"]+)\"\\s+codeSystem=\"2\\.16\\.840\\.1\\.113883\\.6\\.96\"\\s*/>",
                "<translation code=\"$1\" codeSystem=\"2.16.840.1.113883.6.96\" codeSystemName=\"SNOMEDCT\"/>"
            );
            
            // Fix translation elements that might have codeSystemName but wrong format
            xml = xml.replaceAll(
                "<translation\\s+code=\"([^\"]+)\"\\s+codeSystem=\"2\\.16\\.840\\.1\\.113883\\.6\\.96\"\\s+codeSystemName=\"([^\"]+)\"\\s*/>",
                "<translation code=\"$1\" codeSystem=\"2.16.840.1.113883.6.96\" codeSystemName=\"$2\"/>"
            );

            return xml;
        } catch (Exception e) {
            log.error("Error generating C2 XML: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate QRDA-III XML", e);
        }
    }


    private String convertDateToQrdaFormat(String dateStr, boolean isStart) {
        try {
            LocalDate date = LocalDate.parse(dateStr);
            String timeSuffix = isStart ? "000000" : "235959";
            return date.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + timeSuffix;
        } catch (Exception e) {
            return isStart ? "20240101000000" : "20241231235959";
        }
    }

    private String getC2CurrentTimestamp() {
        return ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }
}
