package com.onc.C2C3.service.impl;

import com.onc.EHR.dto.*;
import com.onc.C2C3.service.QRDAExtractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Parser service that extracts data from QRDA-I XML and maps it to QRDA DTOs
 * This is the reverse flow of C1/QRDA generation - extracts instead of populates
 */
@Slf4j
@Service
public class QRDAExtractionServiceImpl implements QRDAExtractionService {

    @Override
    public ExtractedQrdaData extractPatientData(InputStream xmlInput) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xmlInput);
        doc.getDocumentElement().normalize();

        ExtractedQrdaData parsedData = new ExtractedQrdaData();

        PersonalDetailsData personalDetailsData = parsePersonalDetailsData(doc);
        parsedData.setPersonalDetailsData(personalDetailsData);

        // Parse insurance details
        List<InsuranceDetails> insuranceDetails = parseInsuranceDetails(doc);
        parsedData.setInsuranceDetails(insuranceDetails);

        // Parse appointment data
        AppointmentData appointmentData = parseAppointmentData(doc);
        parsedData.setAppointmentData(appointmentData);

        // Parse clinic ID from custodian or author
        String clinicId = parseClinicId(doc);
        parsedData.setClinicId(clinicId);

        // Parse measure information
        String measureId = parseMeasureId(doc);
        String measureName = parseMeasureName(doc);
        parsedData.setMeasureId(measureId);
        parsedData.setMeasureName(measureName);

        // Parse assessments and interventions into FormResponse structure
        FormResponse formResponse = parseFormResponse(doc);
        parsedData.setFormResponse(formResponse);

        // Parse provider details
        DoctorDetailsData providerDetails = parseProviderDetailsFromDoc(doc);
        parsedData.setProviderDetails(providerDetails);

        return parsedData;
    }

     // Parse provider details from Document (extracted from extractPatientData)
    private DoctorDetailsData parseProviderDetailsFromDoc(Document doc) {
        DoctorDetailsData providerDetails = new DoctorDetailsData();

        // Try to extract from documentationOf/performer first (most common location)
        NodeList documentationOfNodes = doc.getElementsByTagName("documentationOf");
        for (int i = 0; i < documentationOfNodes.getLength(); i++) {
            Element docOf = (Element) documentationOfNodes.item(i);
            NodeList performerNodes = docOf.getElementsByTagName("performer");
            for (int j = 0; j < performerNodes.getLength(); j++) {
                Element performer = (Element) performerNodes.item(j);
                NodeList assignedEntityNodes = performer.getElementsByTagName("assignedEntity");
                if (assignedEntityNodes.getLength() > 0) {
                    Element assignedEntity = (Element) assignedEntityNodes.item(0);
                    extractProviderFromAssignedEntity(assignedEntity, providerDetails, doc);
                    if (providerDetails.getNpi() != null || providerDetails.getCms_certificate_number() != null) {
                        break; // Found provider info
                    }
                }
            }
            if (providerDetails.getNpi() != null || providerDetails.getCms_certificate_number() != null) {
                break;
            }
        }

        // If not found, try author/assignedAuthor as fallback
        if ((providerDetails.getNpi() == null || providerDetails.getNpi().isEmpty()) &&
            (providerDetails.getCms_certificate_number() == null || providerDetails.getCms_certificate_number().isEmpty())) {
            NodeList authorNodes = doc.getElementsByTagName("author");
            for (int i = 0; i < authorNodes.getLength(); i++) {
                Element author = (Element) authorNodes.item(i);
                NodeList assignedAuthorNodes = author.getElementsByTagName("assignedAuthor");
                if (assignedAuthorNodes.getLength() > 0) {
                    Element assignedAuthor = (Element) assignedAuthorNodes.item(0);
                    extractProviderFromAssignedEntity(assignedAuthor, providerDetails, doc);
                }
            }
        }

        return providerDetails;
    }

    /**
     * Parse PersonalDetailsData from XML - reverse of addRecordTarget in C1
     */
    private PersonalDetailsData parsePersonalDetailsData(Document doc) {
        PersonalDetailsData personalDetailsData = new PersonalDetailsData();
        PersonalDetailsResponseBlock response = new PersonalDetailsResponseBlock();
        Map<String, PatientInformation> patientInfoMap = new HashMap<>();

        Node patientNode = doc.getElementsByTagName("patient").item(0);
        Node patientRoleNode = doc.getElementsByTagName("patientRole").item(0);
        
        if (patientNode == null || patientRoleNode == null) {
            log.warn("Patient information not found in QRDA document");
            return personalDetailsData;
        }

        Element patient = (Element) patientNode;
        Element patientRole = (Element) patientRoleNode;

        PatientInformation patientInfo = new PatientInformation();

        // Parse name (reverse of name.addGiven/addFamily in C1)
        NodeList nameNodes = patient.getElementsByTagName("name");
        if (nameNodes.getLength() > 0) {
            Element nameEl = (Element) nameNodes.item(0);
            String given = getText(nameEl, "given");
            String family = getText(nameEl, "family");
            patientInfo.setFirstName(given);
            patientInfo.setLastName(family);
        }

        // Parse birth date (reverse of setBirthTime in C1)
        String birthTime = getAttr(patient, "birthTime", "value");
        if (birthTime != null && !birthTime.isEmpty()) {
            // Format: yyyyMMdd or yyyyMMddHHmmss -> yyyy-MM-dd
            String formattedDate = formatDate(birthTime);
            patientInfo.setBirthDate(formattedDate);
        }

        // Parse gender (reverse of setAdministrativeGenderCode in C1)
        String genderCode = null;
        
        // First, try to get code directly from administrativeGenderCode element
        NodeList genderNodes = patient.getElementsByTagNameNS("*", "administrativeGenderCode");
        if (genderNodes.getLength() == 0) {
            genderNodes = patient.getElementsByTagName("administrativeGenderCode");
        }
        
        if (genderNodes.getLength() > 0) {
            Element genderEl = (Element) genderNodes.item(0);
            
            // Check if there's a direct code attribute
            genderCode = genderEl.getAttribute("code");
            if (genderCode == null || genderCode.isEmpty()) {
                // If no direct code, look for translation element
                NodeList translationNodes = genderEl.getElementsByTagNameNS("*", "translation");
                if (translationNodes.getLength() == 0) {
                    translationNodes = genderEl.getElementsByTagName("translation");
                }
                
                if (translationNodes.getLength() > 0) {
                    Element translationEl = (Element) translationNodes.item(0);
                    genderCode = translationEl.getAttribute("code");
                    log.debug("Found gender code in translation element: {}", genderCode);
                }
            } else {
                log.debug("Found gender code directly on administrativeGenderCode: {}", genderCode);
            }
        }
        
        if (genderCode != null && !genderCode.isEmpty()) {
            String genderValue = null;
            
            if ("248152002".equals(genderCode)) {
                genderValue = "M"; // Male SNOMED code
            } else if ("248153007".equals(genderCode)) {
                genderValue = "F"; // Female SNOMED code
            }
            else if ("M".equalsIgnoreCase(genderCode) || "MALE".equalsIgnoreCase(genderCode)) {
                genderValue = "M";
            } else if ("F".equalsIgnoreCase(genderCode) || "FEMALE".equalsIgnoreCase(genderCode)) {
                genderValue = "F";
            }
            else if ("M".equals(genderCode) || "F".equals(genderCode)) {
                genderValue = genderCode;
            }
            else {
                genderValue = genderCode;
                log.warn("Unknown gender code format: {}", genderCode);
            }
            
            if (genderValue != null) {
                patientInfo.setGender(genderValue);
                log.debug("Set patient gender to: {} (from code: {})", genderValue, genderCode);
            }
        } else {
            log.debug("No gender code found in administrativeGenderCode element");
        }

        // Try multiple approaches to find race
        String raceCode = null;
        String raceDisplay = null;
        
        // Method 1: Try namespace-aware search
        NodeList raceNodes = patient.getElementsByTagNameNS("*", "raceCode");
        if (raceNodes != null && raceNodes.getLength() > 0) {
            Element raceCodeEl = (Element) raceNodes.item(0);
            raceCode = raceCodeEl.getAttribute("code");
            raceDisplay = raceCodeEl.getAttribute("displayName");
        }
        
        // Method 2: Try direct tag name search (in case namespace doesn't match)
        if ((raceCode == null || raceCode.isEmpty()) && raceDisplay == null) {
            NodeList allRaceNodes = doc.getElementsByTagName("raceCode");
            for (int i = 0; i < allRaceNodes.getLength(); i++) {
                Element raceEl = (Element) allRaceNodes.item(i);
                // Check if this raceCode is within the patient element
                if (patient.equals(raceEl.getParentNode()) || isDescendantOf(raceEl, patient)) {
                    raceCode = raceEl.getAttribute("code");
                    raceDisplay = raceEl.getAttribute("displayName");
                    break;
                }
            }
        }
        
        if (raceCode != null || raceDisplay != null) {
            // Map code to display name (reverse of C1 mapping)
            String raceValue = mapRaceCodeToDisplay(raceCode, raceDisplay);
            if (raceValue != null && !raceValue.isEmpty()) {
                patientInfo.setRace(List.of(raceValue));
                log.debug("Extracted race: code={}, display={}, mapped={}", raceCode, raceDisplay, raceValue);
            }
        } else {
            log.debug("No race information found in XML");
        }

        // Parse ethnicity (reverse of setEthnicGroupCode in C1)
        // Try multiple approaches to find ethnicity
        String ethnicityCode = null;
        String ethnicityDisplay = null;
        
        // Method 1: Try namespace-aware search
        NodeList ethnicityNodes = patient.getElementsByTagNameNS("*", "ethnicGroupCode");
        if (ethnicityNodes != null && ethnicityNodes.getLength() > 0) {
            Element ethnicityCodeEl = (Element) ethnicityNodes.item(0);
            ethnicityCode = ethnicityCodeEl.getAttribute("code");
            ethnicityDisplay = ethnicityCodeEl.getAttribute("displayName");
        }
        
        // Method 2: Try direct tag name search
        if ((ethnicityCode == null || ethnicityCode.isEmpty()) && ethnicityDisplay == null) {
            NodeList allEthnicityNodes = doc.getElementsByTagName("ethnicGroupCode");
            for (int i = 0; i < allEthnicityNodes.getLength(); i++) {
                Element ethnicityEl = (Element) allEthnicityNodes.item(i);
                // Check if this ethnicGroupCode is within the patient element
                if (patient.equals(ethnicityEl.getParentNode()) || isDescendantOf(ethnicityEl, patient)) {
                    ethnicityCode = ethnicityEl.getAttribute("code");
                    ethnicityDisplay = ethnicityEl.getAttribute("displayName");
                    break;
                }
            }
        }
        
        if (ethnicityCode != null || ethnicityDisplay != null) {
            // Map code to display name
            String ethnicityValue = mapEthnicityCodeToDisplay(ethnicityCode, ethnicityDisplay);
            if (ethnicityValue != null && !ethnicityValue.isEmpty()) {
                patientInfo.setEthnicity(List.of(ethnicityValue));
                log.debug("Extracted ethnicity: code={}, display={}, mapped={}", ethnicityCode, ethnicityDisplay, ethnicityValue);
            }
        } else {
            log.debug("No ethnicity information found in XML");
        }

        // Parse address (reverse of addStreetAddressLine in C1)
        Element addr = (Element) patientRole.getElementsByTagName("addr").item(0);
        if (addr != null) {
            String street = getText(addr, "streetAddressLine");
            String city = getText(addr, "city");
            String state = getText(addr, "state");
            String postalCode = getText(addr, "postalCode");
            
            patientInfo.setAddressLine1(street);
            patientInfo.setCity(city);
            patientInfo.setState(state);
            patientInfo.setZipCode(postalCode);
        }

        // Parse phone (reverse of TEL creation in C1)
        NodeList telecomNodes = patientRole.getElementsByTagName("telecom");
        List<Phone> phones = new ArrayList<>();
        for (int i = 0; i < telecomNodes.getLength(); i++) {
            Element telecom = (Element) telecomNodes.item(i);
            String value = telecom.getAttribute("value");
            if (value != null && value.startsWith("tel:")) {
                Phone phone = new Phone();
                phone.setContact(value.substring(4)); // Remove "tel:" prefix
                // Try to determine label from use attribute
                NodeList useNodes = telecom.getElementsByTagName("use");
                if (useNodes.getLength() > 0) {
                    String use = ((Element) useNodes.item(0)).getAttribute("code");
                    if ("HP".equals(use)) {
                        phone.setLabel("Home");
                    } else if ("WP".equals(use)) {
                        phone.setLabel("Work");
                    }
                }
                phones.add(phone);
            } else if (value != null && value.startsWith("mailto:")) {
                patientInfo.setEmail(value.substring(7)); // Remove "mailto:" prefix
            }
        }
        patientInfo.setPhone(phones);

        // Add patient info to map (using a default key like C1 does)
        patientInfoMap.put("blank1653622136916", patientInfo);
        response.setPatientInformation(patientInfoMap);
        personalDetailsData.setResponse(response);

        return personalDetailsData;
    }

    /**
     * Parse InsuranceDetails from XML - reverse of insurance section in C1
     */
    private List<InsuranceDetails> parseInsuranceDetails(Document doc) {
        List<InsuranceDetails> insuranceList = new ArrayList<>();
        
        // Look for payer observations (code 48768-6)
        NodeList obsNodes = doc.getElementsByTagName("observation");
        for (int i = 0; i < obsNodes.getLength(); i++) {
            Element obs = (Element) obsNodes.item(i);
            String code = getAttr(obs, "code", "code");
            
            if ("48768-6".equals(code)) { // Payment source code
                InsuranceDetails insurance = new InsuranceDetails();
                
                // Parse payer information
                Element valueEl = (Element) obs.getElementsByTagName("value").item(0);
                if (valueEl != null) {
                    String payerCode = valueEl.getAttribute("code");
                    String payerDisplay = valueEl.getAttribute("displayName");
                    
                    InsurancePayer payer = new InsurancePayer();
                    payer.setPayer_id(payerCode);
                    payer.setName(payerDisplay);
                    insurance.setInsurance_payer(payer);
                }
                
                // Parse dates
                Element effTime = (Element) obs.getElementsByTagName("effectiveTime").item(0);
                if (effTime != null) {
                    String startDate = getAttr(effTime, "low", "value");
                    String endDate = getAttr(effTime, "high", "value");
                    
                    if (startDate != null && !startDate.isEmpty()) {
                        insurance.setPlan_start_date(formatDate(startDate));
                    }
                    if (endDate != null && !endDate.isEmpty()) {
                        insurance.setPlan_end_date(formatDate(endDate));
                    }
                }
                
                insuranceList.add(insurance);
            }
        }
        
        return insuranceList;
    }

    /**
     * Parse AppointmentData from XML - reverse of encounter/appointment section in C1
     */
    private AppointmentData parseAppointmentData(Document doc) {
        AppointmentData appointmentData = new AppointmentData();
        List<Appointment> appointments = new ArrayList<>();
        
        log.info("=== PARSING APPOINTMENT/ENCOUNTER DATA FROM QRDA XML ===");
        
        // Parse encounters (which represent appointments in QRDA)
        NodeList encounterNodes = doc.getElementsByTagName("encounter");
        log.info("Found {} encounter element(s) in QRDA XML", encounterNodes.getLength());
        
        int validEncounters = 0;
        for (int i = 0; i < encounterNodes.getLength(); i++) {
            Element encounter = (Element) encounterNodes.item(i);
            String classCode = encounter.getAttribute("classCode");
            
            log.info("Encounter {}: classCode={}", i + 1, classCode);
            
            if (!"ENC".equals(classCode)) {
                log.debug("Encounter {} skipped - classCode is not 'ENC'", i + 1);
                continue;
            }
            
            validEncounters++;
            Appointment appointment = new Appointment();
            
            // Parse encounter ID
            String id = getAttr(encounter, "id", "extension");
            log.info("Encounter {} - ID: {}", validEncounters, id);
            if (id != null && !id.isEmpty()) {
                try {
                    appointment.setAppointment_id(Integer.parseInt(id));
                    log.info("Encounter {} - Parsed appointment_id: {}", validEncounters, appointment.getAppointment_id());
                } catch (NumberFormatException e) {
                    // Use hash if not numeric
                    appointment.setAppointment_id(id.hashCode());
                    log.warn("Encounter {} - ID '{}' is not numeric, using hash: {}", validEncounters, id, appointment.getAppointment_id());
                }
            } else {
                log.warn("Encounter {} - No ID found", validEncounters);
            }
            
            // Parse encounter code/type
            String code = getAttr(encounter, "code", "code");
            String codeSystem = getAttr(encounter, "code", "codeSystem");
            String description = getText(encounter, "text");
            
            log.info("Encounter {} - Code: {}, CodeSystem: {}, Description: {}", 
                    validEncounters, code, codeSystem, description);
            
            // Check if this is a hospice encounter - extract as intervention instead of appointment
            if (isHospiceEncounter(code, codeSystem, description)) {
                log.info("Encounter {} - HOSPICE ENCOUNTER detected (code={}, codeSystem={}, description={}). " +
                        "This will be added as an intervention entry for DENEX evaluation.",
                        validEncounters, code, codeSystem, description);
                validEncounters--; // Adjust counter since we're skipping this as appointment
                // Note: Hospice encounters will be added as interventions in parseFormResponse
                continue; // Skip this encounter - don't add it to appointments
            }
            
            // Check if this is an inpatient encounter - SKIP these (don't upload as appointments)
            if (isInpatientEncounter(code, codeSystem, description)) {
                log.warn("Encounter {} - SKIPPED: Inpatient encounter detected (code={}, codeSystem={}, description={}). " +
                        "Inpatient encounters are care settings (hospital admissions), not visit appointments. " +
                        "They will not be uploaded to EHR as appointments.",
                        validEncounters, code, codeSystem, description);
                validEncounters--; // Adjust counter since we're skipping this
                continue; // Skip this encounter - don't add it to appointments
            }
            
            // Check if this is an Emergency Department Visit encounter - SKIP these (don't upload as appointments)
            if (isEmergencyDepartmentVisit(code, codeSystem, description)) {
                log.warn("Encounter {} - SKIPPED: Emergency Department Visit encounter detected (code={}, codeSystem={}, description={}). " +
                        "Emergency Department Visit encounters will not be uploaded to EHR as appointments.",
                        validEncounters, code, codeSystem, description);
                validEncounters--; // Adjust counter since we're skipping this
                continue; // Skip this encounter - don't add it to appointments
            }
            
            appointment.setType(description != null ? description : code);
            
            List<AppointmentCategory> categories = new ArrayList<>();
            if (code != null && codeSystem != null && codeSystem.contains("2.16.840.1.113883.6.12")) {
                AppointmentCategory category = new AppointmentCategory();
                if ("99203".equals(code) || "99204".equals(code) || "99205".equals(code)) {
                    // New patient office visits → Initial Evaluation
                    category.setName("Initial Evaluation");
                    category.setMinutes(30); // Default duration
                    log.info("Encounter {} - Mapped CPT code {} to 'Initial Evaluation'", validEncounters, code);
                } else if ("99213".equals(code) || "99214".equals(code) || "99215".equals(code) ||
                          "99211".equals(code) || "99212".equals(code)) {
                    // Established patient office visits → Follow-up
                    category.setName("Follow-up");
                    category.setMinutes(15); // Default duration
                } else {
                    // Default to Follow-up for other CPT codes
                    category.setName("Follow-up");
                    category.setMinutes(15);
                }
                categories.add(category);
            } else {
                // If no CPT code, default to Follow-up
                AppointmentCategory category = new AppointmentCategory();
                category.setName("Follow-up");
                category.setMinutes(15);
                log.info("Encounter {} - No CPT code found (code={}, codeSystem={}), defaulting to 'Follow-up'", 
                        validEncounters, code, codeSystem);
                categories.add(category);
            }
            appointment.setCategory(categories);
            
            // Parse effective time (appointment date/time)
            Element effectiveTime = (Element) encounter.getElementsByTagName("effectiveTime").item(0);
            if (effectiveTime != null) {
                String startDate = getAttr(effectiveTime, "low", "value");
                String endDate = getAttr(effectiveTime, "high", "value");
                
                log.info("Encounter {} - Raw dates from XML: startDate={}, endDate={}", 
                        validEncounters, startDate, endDate);
                
                if (startDate != null && !startDate.isEmpty()) {
                    // Convert to epoch timestamp (seconds) - QRDA stores dates in yyyyMMddHHmmss format
                    String epochStart = convertDateTimeToEpoch(startDate);
                    appointment.setDate_time(epochStart);
                    log.info("Encounter {} - Converted startDate: {} -> epoch: {}", 
                            validEncounters, startDate, epochStart);
                } else {
                    log.warn("Encounter {} - No startDate found in effectiveTime", validEncounters);
                }
                
                if (endDate != null && !endDate.isEmpty()) {
                    String epochEnd = convertDateTimeToEpoch(endDate);
                    appointment.setEnd_date_time(epochEnd);
                    log.info("Encounter {} - Converted endDate: {} -> epoch: {}", 
                            validEncounters, endDate, epochEnd);
                } else {
                    log.warn("Encounter {} - No endDate found in effectiveTime", validEncounters);
                }
            } else {
                log.warn("Encounter {} - No effectiveTime element found", validEncounters);
            }
            
            // Parse status
            String status = getAttr(encounter, "statusCode", "code");
            appointment.setAppointment_status(status != null ? status : "completed");
            log.info("Encounter {} - Status: {}", validEncounters, appointment.getAppointment_status());
            
            log.info("Encounter {} - Complete appointment data: appointment_id={}, type={}, date_time={}, end_date_time={}, status={}, categories={}",
                    validEncounters, 
                    appointment.getAppointment_id(),
                    appointment.getType(),
                    appointment.getDate_time(),
                    appointment.getEnd_date_time(),
                    appointment.getAppointment_status(),
                    appointment.getCategory() != null ? appointment.getCategory().stream()
                            .map(c -> c != null ? c.getName() : "null").collect(java.util.stream.Collectors.joining(", ")) : "null");
            
            appointments.add(appointment);
        }
        
        appointmentData.setAppointments(appointments);
        appointmentData.setTotal(appointments.size());
        appointmentData.setNo_of_records(appointments.size());
        
        log.info("=== APPOINTMENT PARSING COMPLETE ===");
        log.info("Total encounters found: {}, Valid encounters parsed: {}, Appointments created: {}", 
                encounterNodes.getLength(), validEncounters, appointments.size());
        
        if (appointments.isEmpty()) {
            log.warn("WARNING: No appointments were extracted from QRDA XML!");
        } else {
            log.info("Successfully extracted {} appointment(s) from QRDA XML", appointments.size());
        }
        
        return appointmentData;
    }

    /**
     * Parse clinic ID from custodian or author organization
     */
    private String parseClinicId(Document doc) {
        // Try custodian first
        NodeList custodianNodes = doc.getElementsByTagName("custodian");
        if (custodianNodes.getLength() > 0) {
            Element custodian = (Element) custodianNodes.item(0);
            NodeList orgNodes = custodian.getElementsByTagName("representedCustodianOrganization");
            if (orgNodes.getLength() > 0) {
                Element org = (Element) orgNodes.item(0);
                NodeList idNodes = org.getElementsByTagName("id");
                for (int i = 0; i < idNodes.getLength(); i++) {
                    Element idEl = (Element) idNodes.item(i);
                    String root = idEl.getAttribute("root");
                    if ("2.16.840.1.113883.19.5".equals(root)) {
                        String extension = idEl.getAttribute("extension");
                        if (extension != null && !extension.isEmpty()) {
                            return extension;
                        }
                    }
                }
            }
        }
        
        // Try author as fallback
        NodeList authorNodes = doc.getElementsByTagName("author");
        if (authorNodes.getLength() > 0) {
            Element author = (Element) authorNodes.item(0);
            NodeList orgNodes = author.getElementsByTagName("representedOrganization");
            if (orgNodes.getLength() > 0) {
                Element org = (Element) orgNodes.item(0);
                NodeList idNodes = org.getElementsByTagName("id");
                for (int i = 0; i < idNodes.getLength(); i++) {
                    Element idEl = (Element) idNodes.item(i);
                    String root = idEl.getAttribute("root");
                    if ("2.16.840.1.113883.19.5".equals(root)) {
                        String extension = idEl.getAttribute("extension");
                        if (extension != null && !extension.isEmpty()) {
                            return extension;
                        }
                    }
                }
            }
        }
        
        return "762"; // Default fallback
    }

    /**
     * Parse measure ID from measure section
     */
    private String parseMeasureId(Document doc) {
        NodeList organizerNodes = doc.getElementsByTagName("organizer");
        for (int i = 0; i < organizerNodes.getLength(); i++) {
            Element organizer = (Element) organizerNodes.item(i);
            NodeList refNodes = organizer.getElementsByTagName("reference");
            for (int j = 0; j < refNodes.getLength(); j++) {
                Element ref = (Element) refNodes.item(j);
                Element externalDoc = (Element) ref.getElementsByTagName("externalDocument").item(0);
                if (externalDoc != null) {
                    NodeList idNodes = externalDoc.getElementsByTagName("id");
                    for (int k = 0; k < idNodes.getLength(); k++) {
                        Element idEl = (Element) idNodes.item(k);
                        String root = idEl.getAttribute("root");
                        if ("2.16.840.1.113883.4.738".equals(root)) {
                            String extension = idEl.getAttribute("extension");
                            if (extension != null && !extension.isEmpty()) {
                                return extension;
                            }
                        }
                    }
                }
            }
        }
        return "";
    }

    /**
     * Parse measure name from measure section
     */
    private String parseMeasureName(Document doc) {
        NodeList organizerNodes = doc.getElementsByTagName("organizer");
        for (int i = 0; i < organizerNodes.getLength(); i++) {
            Element organizer = (Element) organizerNodes.item(i);
            NodeList refNodes = organizer.getElementsByTagName("reference");
            for (int j = 0; j < refNodes.getLength(); j++) {
                Element ref = (Element) refNodes.item(j);
                Element externalDoc = (Element) ref.getElementsByTagName("externalDocument").item(0);
                if (externalDoc != null) {
                    Element textEl = (Element) externalDoc.getElementsByTagName("text").item(0);
                    if (textEl != null) {
                        return textEl.getTextContent().trim();
                    }
                }
            }
        }
        return "";
    }

    // Helper methods
    private String getText(Element parent, String tag) {
        if (parent == null) return "";
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() > 0 && nodes.item(0).getNodeType() == Node.ELEMENT_NODE) {
            return nodes.item(0).getTextContent().trim();
        }
        return "";
    }

    private String getAttr(Element parent, String tag, String attr) {
        if (parent == null) return "";
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() > 0) {
            Node node = nodes.item(0);
            if (node instanceof Element) {
                String value = ((Element) node).getAttribute(attr);
                return value != null ? value.trim() : "";
            }
        }
        return "";
    }

    private String formatDate(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            if (value.length() >= 8) {
                // yyyyMMdd -> yyyy-MM-dd
                return value.substring(0, 4) + "-" + value.substring(4, 6) + "-" + value.substring(6, 8);
            }
        } catch (Exception e) {
            log.debug("Error formatting date: {}", value, e);
        }
        return value;
    }

    private String formatDateTime(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            if (value.length() >= 14) {
                // yyyyMMddHHmmss -> yyyy-MM-ddTHH:mm:ss
                return value.substring(0, 4) + "-" + value.substring(4, 6) + "-" + value.substring(6, 8) +
                       "T" + value.substring(8, 10) + ":" + value.substring(10, 12) + ":" + value.substring(12, 14);
            } else if (value.length() >= 8) {
                return formatDate(value) + "T00:00:00";
            }
        } catch (Exception e) {
            log.debug("Error formatting datetime: {}", value, e);
        }
        return value;
    }

    /**
     * Convert QRDA datetime string to epoch seconds
     * 
     * IMPORTANT: QRDA datetime values represent times in Eastern Time (EST/EDT)
     * The EHR expects timestamps that, when displayed in Eastern Time, show the exact time from QRDA
     * 
     * Approach: 
     * - Parse QRDA datetime (e.g., "20230215080000" = Feb 15, 2023 8:00 AM)
     * - Treat it as Eastern Time (America/New_York)
     * - Convert to UTC epoch seconds
     * - EHR will convert back to Eastern Time for display, showing the correct time
     * 
     * - For datetime with time component (yyyyMMddHHmmss): Interpret as Eastern Time
     * - For date-only (yyyyMMdd): Use noon Eastern Time to avoid date boundary issues
     */
    private String convertDateTimeToEpoch(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            LocalDateTime dateTime;
            if (value.length() >= 14) {
                // yyyyMMddHHmmss format - has time component
                // QRDA stores this in Eastern Time (EST/EDT)
                int year = Integer.parseInt(value.substring(0, 4));
                int month = Integer.parseInt(value.substring(4, 6));
                int day = Integer.parseInt(value.substring(6, 8));
                int hour = Integer.parseInt(value.substring(8, 10));
                int minute = Integer.parseInt(value.substring(10, 12));
                int second = Integer.parseInt(value.substring(12, 14));
                dateTime = LocalDateTime.of(year, month, day, hour, minute, second);
                log.debug("Parsed datetime with time component: {} -> {}", value, dateTime);
            } else if (value.length() >= 8) {
                // yyyyMMdd format - date only
                // Use noon Eastern Time to avoid timezone boundary issues
                int year = Integer.parseInt(value.substring(0, 4));
                int month = Integer.parseInt(value.substring(4, 6));
                int day = Integer.parseInt(value.substring(6, 8));
                dateTime = LocalDateTime.of(year, month, day, 12, 0, 0); // Noon Eastern Time
                log.debug("Date-only value {} converted to LocalDateTime at noon: {}", value, dateTime);
            } else {
                log.warn("Invalid date format for epoch conversion: {}", value);
                return String.valueOf(System.currentTimeMillis() / 1000); // Fallback to current time
            }
            
            // Convert LocalDateTime to epoch seconds
            // Step 1: Interpret the datetime as Eastern Time (America/New_York)
            // Step 2: Convert to UTC epoch seconds
            // When EHR displays this UTC epoch in Eastern Time, it will show the correct time
            java.time.ZoneId easternZone = java.time.ZoneId.of("America/New_York");
            long epochSeconds = dateTime.atZone(easternZone).toEpochSecond();
            
            // Log for debugging
            java.time.ZonedDateTime zdtEastern = dateTime.atZone(easternZone);
            java.time.ZonedDateTime zdtUTC = java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneId.of("UTC"));
            
            log.debug("Converted {} (Eastern Time: {}) to epoch seconds (UTC): {} (UTC: {})", 
                    value, zdtEastern, epochSeconds, zdtUTC);
            return String.valueOf(epochSeconds);
        } catch (Exception e) {
            log.error("Error converting datetime to epoch: {}", value, e);
            return String.valueOf(System.currentTimeMillis() / 1000); // Fallback to current time
        }
    }

    /**
     * Map race code to display name (reverse of C1 mapping)
     */
    private String mapRaceCodeToDisplay(String code, String display) {
        if (display != null && !display.isEmpty()) {
            return display;
        }
        // Reverse mapping from C1
        switch (code) {
            case "2106-3": return "White";
            case "2054-5": return "Black or African American";
            case "2028-9": return "Asian";
            case "1002-5": return "American Indian or Alaska Native";
            case "2076-8": return "Native Hawaiian or Other Pacific Islander";
            default: return code != null ? code : "";
        }
    }

    /**
     * Map ethnicity code to display name (reverse of C1 mapping)
     */
    private String mapEthnicityCodeToDisplay(String code, String display) {
        if (display != null && !display.isEmpty()) {
            return display;
        }
        // Reverse mapping from C1
        switch (code) {
            case "2135-2": return "Hispanic or Latino";
            case "2186-5": return "Not Hispanic or Latino";
            default: return code != null ? code : "";
        }
    }

    /**
     * Parse FormResponse from XML - extracts Assessment and Intervention sections
     * Reverse of QRDA package: builds FormResponse structure with CodeSection objects
     */
    private FormResponse parseFormResponse(Document doc) {
        log.info("=== PARSING FORM RESPONSE (ASSESSMENT & INTERVENTION) FROM QRDA XML ===");
        
        FormResponse formResponse = new FormResponse();
        Map<String, CodeSection> assessmentMap = new LinkedHashMap<>();
        Map<String, CodeSection> interventionMap = new LinkedHashMap<>();
        
        // Parse assessments
        parseAssessmentsIntoFormResponse(doc, assessmentMap);
        formResponse.setAssessment(assessmentMap);
        
        // Parse interventions
        parseInterventionsIntoFormResponse(doc, interventionMap);
        
        // Extract hospice encounters and add them as interventions
        parseHospiceEncountersIntoFormResponse(doc, interventionMap);
        
        // Extract hospice codes from observation values and add them as interventions
        parseHospiceObservationsIntoFormResponse(doc, interventionMap);
        
        formResponse.setIntervention(interventionMap);
        
        log.info("=== FORM RESPONSE PARSING COMPLETE ===");
        log.info("Assessment entries: {}, Intervention entries: {}", 
                assessmentMap.size(), interventionMap.size());
        
        return formResponse;
    }
    
    /**
     * Parse assessments from XML into FormResponse structure
     * Each assessment becomes a CodeSection with LOINC codes
     */
    private void parseAssessmentsIntoFormResponse(Document doc, Map<String, CodeSection> assessmentMap) {
        log.info("=== PARSING ASSESSMENT DATA INTO FORM RESPONSE ===");
        
        NodeList obsNodes = doc.getElementsByTagNameNS("*", "observation");
        if (obsNodes.getLength() == 0) {
            obsNodes = doc.getElementsByTagName("observation");
        }
        
        log.info("Found {} observation element(s) in QRDA XML", obsNodes.getLength());
        
        int validAssessments = 0;
        int skippedCount = 0;
        for (int i = 0; i < obsNodes.getLength(); i++) {
            Element obs = (Element) obsNodes.item(i);
            
            // Check if this is an assessment (template ID 2.16.840.1.113883.10.20.24.3.144)
            NodeList templateIds = obs.getElementsByTagNameNS("*", "templateId");
            if (templateIds.getLength() == 0) {
                templateIds = obs.getElementsByTagName("templateId");
            }
            
            boolean isAssessment = false;
            String templateRoot = null;
            for (int j = 0; j < templateIds.getLength(); j++) {
                Element templateIdEl = (Element) templateIds.item(j);
                templateRoot = templateIdEl.getAttribute("root");
                if ("2.16.840.1.113883.10.20.24.3.144".equals(templateRoot)) {
                    isAssessment = true;
                    break;
                }
            }
            
            if (!isAssessment) {
                skippedCount++;
                log.debug("Observation {} skipped - template ID '{}' is not assessment template (2.16.840.1.113883.10.20.24.3.144)", 
                        i + 1, templateRoot);
                continue;
            }
            
            validAssessments++;
            
            String id = getAttr(obs, "id", "extension");
            String code = getAttr(obs, "code", "code");
            String codeSystem = getAttr(obs, "code", "codeSystem");
            String statusCode = getAttr(obs, "statusCode", "code");
            
            String time = getAttr(obs, "effectiveTime", "value");
            
            // If no effectiveTime, try to get date from author/time (common for assessment orders)
            if (time == null || time.isEmpty()) {
                NodeList authorNodes = obs.getElementsByTagNameNS("*", "author");
                if (authorNodes.getLength() == 0) {
                    authorNodes = obs.getElementsByTagName("author");
                }
                
                if (authorNodes.getLength() > 0) {
                    Element author = (Element) authorNodes.item(0);
                    NodeList timeNodes = author.getElementsByTagNameNS("*", "time");
                    if (timeNodes.getLength() == 0) {
                        timeNodes = author.getElementsByTagName("time");
                    }
                    
                    if (timeNodes.getLength() > 0) {
                        Element timeElement = (Element) timeNodes.item(0);
                        time = timeElement.getAttribute("value");
                        log.info("Assessment {} - Found author/time date: {}", validAssessments, time);
                    }
                }
            }
            
            String description = getText(obs, "text");
            
            log.info("Processing assessment {}: id={}, code={}, codeSystem={}, status={}, time={}, description={}", 
                    validAssessments, id, code, codeSystem, statusCode, time, description);
            
            CodeSection codeSection = new CodeSection();
            List<LoincCode> loincCodes = new ArrayList<>();
            List<SnomedCode> snomedCodes = new ArrayList<>();
            
            if (code != null && !code.isEmpty()) {
                String cleanCode = code;
                
                boolean isLoinc = "2.16.840.1.113883.6.1".equals(codeSystem);
                boolean isSnomed = "2.16.840.1.113883.6.96".equals(codeSystem);
                
                if (isLoinc || (codeSystem == null || codeSystem.isEmpty() && !code.startsWith("SCT-"))) {
                    if (code.startsWith("LC-")) {
                        cleanCode = code.substring(3);
                    }
                    
                    LoincCode loincCode = new LoincCode();
                    loincCode.setCode("LC-" + cleanCode);
                    loincCode.setDescription(description != null ? description : "");
                    loincCode.setStatus(statusCode != null ? statusCode : "Active");
                    
                    // Convert time to ISO datetime format
                    if (time != null && !time.isEmpty()) {
                        try {
                            String isoDateTime = convertQrdaDateTimeToIso(time);
                            loincCode.setStartDate(isoDateTime);
                            log.debug("Assessment {} - Set startDate: {} -> {}", validAssessments, time, isoDateTime);
                        } catch (Exception e) {
                            log.warn("Failed to parse assessment time: {}", time, e);
                            loincCode.setStartDate("");
                        }
                    } else {
                        loincCode.setStartDate("");
                        log.debug("Assessment {} - No start date found", validAssessments);
                    }
                    
                    loincCode.setEndDate("");
                    loincCodes.add(loincCode);
                    log.info("Added LOINC code to assessment: LC-{}, description: {}", cleanCode, description);
                }
                else if (isSnomed || code.startsWith("SCT-")) {
                    if (code.startsWith("SCT-")) {
                        cleanCode = code.substring(4);
                    }
                    
                    SnomedCode snomedCode = new SnomedCode();
                    snomedCode.setCode("SCT-" + cleanCode);
                    snomedCode.setConceptId("SCT-" + cleanCode);
                    snomedCode.setDescription(description != null ? description : "");
                    snomedCode.setTerm(description != null ? description : "");
                    snomedCode.setStatus(statusCode != null ? statusCode : "Active");
                    
                    // Convert time to ISO datetime format
                    if (time != null && !time.isEmpty()) {
                        try {
                            String isoDateTime = convertQrdaDateTimeToIso(time);
                            snomedCode.setStartDate(isoDateTime);
                            log.debug("Assessment {} - Set startDate: {} -> {}", validAssessments, time, isoDateTime);
                        } catch (Exception e) {
                            log.warn("Failed to parse assessment time: {}", time, e);
                            snomedCode.setStartDate("");
                        }
                    } else {
                        snomedCode.setStartDate("");
                        log.debug("Assessment {} - No start date found", validAssessments);
                    }
                    
                    snomedCode.setEndDate("");
                    snomedCodes.add(snomedCode);
                    log.info("Added SNOMED code to assessment: SCT-{}, description: {}", cleanCode, description);
                } else {
                    log.warn("Assessment {} has unsupported codeSystem: {} (code: {}). Skipping code.", 
                            validAssessments, codeSystem, code);
                }
            } else {
                log.warn("Assessment {} has no code. Skipping.", validAssessments);
            }
            
            if (loincCodes.isEmpty()) {
                codeSection.setLoincCodes("");
            } else {
                codeSection.setLoincCodes(loincCodes);
            }
            
            if (snomedCodes.isEmpty()) {
                codeSection.setSnomedCodes("");
            } else {
                codeSection.setSnomedCodes(snomedCodes);
            }
            
            // Use assessment ID as key, or generate one if missing
            String key = (id != null && !id.isEmpty()) ? id : "assessment_" + validAssessments;
            assessmentMap.put(key, codeSection);
            
            log.info("Assessment {} extracted: key={}, code={}, codeSystem={}, status={}, time={}, description={}, loincCodes={}", 
                    validAssessments, key, code, codeSystem, statusCode, time, description, loincCodes.size());
        }
        
        log.info("=== ASSESSMENT PARSING COMPLETE ===");
        log.info("Total observations found: {}, Skipped: {}, Valid assessments parsed: {}, Assessment entries created: {}", 
                obsNodes.getLength(), skippedCount, validAssessments, assessmentMap.size());
        
        if (assessmentMap.isEmpty()) {
            log.warn("WARNING: No assessments were extracted from QRDA XML!");
            log.warn("This could mean: 1) No observations with template ID 2.16.840.1.113883.10.20.24.3.144 found, " +
                    "2) Observations found but no matching codeSystem, or 3) XML structure is different than expected");
        } else {
            log.info("Successfully extracted {} assessment entry(ies) from QRDA XML", assessmentMap.size());
        }
    }
    
    /**
     * Parse interventions from XML into FormResponse structure
     * Each intervention becomes a CodeSection with SNOMED codes
     */
    private void parseInterventionsIntoFormResponse(Document doc, Map<String, CodeSection> interventionMap) {
        log.info("=== PARSING INTERVENTION DATA INTO FORM RESPONSE ===");
        
        // Try both namespace-aware and non-namespace-aware approaches
        NodeList actNodes = doc.getElementsByTagNameNS("*", "act");
        if (actNodes.getLength() == 0) {
            actNodes = doc.getElementsByTagName("act");
        }
        
        log.info("Found {} act element(s) in QRDA XML", actNodes.getLength());
        
        int validInterventions = 0;
        int skippedCount = 0;
        for (int i = 0; i < actNodes.getLength(); i++) {
            Element act = (Element) actNodes.item(i);
            String classCode = act.getAttribute("classCode");
            
            log.debug("Act {}: classCode={}", i + 1, classCode);
            
            if (!"ACT".equals(classCode)) {
                skippedCount++;
                log.debug("Act {} skipped - classCode is not 'ACT' (found: {})", i + 1, classCode);
                continue;
            }

            // Check if this is an intervention (template IDs 2.16.840.1.113883.10.20.24.3.31 or 3.32)
            NodeList templateIds = act.getElementsByTagNameNS("*", "templateId");
            if (templateIds.getLength() == 0) {
                templateIds = act.getElementsByTagName("templateId");
            }
            
            boolean isIntervention = false;
            String interventionTemplateId = null;
            for (int j = 0; j < templateIds.getLength(); j++) {
                String root = ((Element) templateIds.item(j)).getAttribute("root");
                if ("2.16.840.1.113883.10.20.24.3.31".equals(root) ||
                        "2.16.840.1.113883.10.20.24.3.32".equals(root)) {
                    isIntervention = true;
                    interventionTemplateId = root;
                    break;
                }
            }
            
            if (!isIntervention) {
                skippedCount++;
                log.debug("Act {} skipped - not an intervention (template IDs don't match 2.16.840.1.113883.10.20.24.3.31 or 3.32)", i + 1);
                continue;
            }
            
            validInterventions++;
            
            String id = getAttr(act, "id", "extension");
            String code = getAttr(act, "code", "code");
            String codeSystem = getAttr(act, "code", "codeSystem");
            String status = getAttr(act, "statusCode", "code");
            String moodCode = act.getAttribute("moodCode");
            String description = getText(act, "text");

            log.info("Processing intervention {}: id={}, code={}, codeSystem={}, status={}, moodCode={}, description={}", 
                    validInterventions, id, code, codeSystem, status, moodCode, description);

            // Create CodeSection with SNOMED code
            CodeSection codeSection = new CodeSection();
            List<SnomedCode> snomedCodes = new ArrayList<>();
            
            // Process if it's a SNOMED code (codeSystem 2.16.840.1.113883.6.96)
            // Also handle cases where code might already have SCT- prefix
            if (code != null && !code.isEmpty()) {
                String cleanCode = code;
                if (code.startsWith("SCT-")) {
                    cleanCode = code.substring(4);
                }
                
                // Check if it's SNOMED code system
                boolean isSnomed = "2.16.840.1.113883.6.96".equals(codeSystem);
                
                if (isSnomed || codeSystem == null || codeSystem.isEmpty()) {
                    // Assume SNOMED if codeSystem matches or is missing
                    SnomedCode snomedCode = new SnomedCode();
                    snomedCode.setCode("SCT-" + cleanCode);
                    snomedCode.setConceptId("SCT-" + cleanCode);
                    snomedCode.setDescription(description != null ? description : "");
                    snomedCode.setTerm(description != null ? description : "");
                    snomedCode.setStatus(status != null ? status : "Active");
                    
                    // Parse effectiveTime first (preferred)
                    NodeList effectiveTimeNodes = act.getElementsByTagNameNS("*", "effectiveTime");
                    if (effectiveTimeNodes.getLength() == 0) {
                        effectiveTimeNodes = act.getElementsByTagName("effectiveTime");
                    }
                    
                    Element effectiveTime = effectiveTimeNodes.getLength() > 0 ? (Element) effectiveTimeNodes.item(0) : null;
                    String startDate = null;
                    String endDate = null;
                    
                    if (effectiveTime != null) {
                        // Get start date from low element
                        Element lowEl = (Element) effectiveTime.getElementsByTagNameNS("*", "low").item(0);
                        if (lowEl == null) {
                            lowEl = (Element) effectiveTime.getElementsByTagName("low").item(0);
                        }
                        if (lowEl != null) {
                            startDate = lowEl.getAttribute("value");
                            // Check for nullFlavor
                            if ((startDate == null || startDate.isEmpty()) && lowEl.hasAttribute("nullFlavor")) {
                                startDate = null;
                            }
                        }
                        
                        // Get end date from high element and check for nullFlavor
                        Element highEl = (Element) effectiveTime.getElementsByTagNameNS("*", "high").item(0);
                        if (highEl == null) {
                            highEl = (Element) effectiveTime.getElementsByTagName("high").item(0);
                        }
                        if (highEl != null) {
                            endDate = highEl.getAttribute("value");
                            // Check for nullFlavor (like nullFlavor='UNK') - means ongoing/unknown end date
                            if ((endDate == null || endDate.isEmpty()) && highEl.hasAttribute("nullFlavor")) {
                                endDate = null; // Leave as null to indicate ongoing/unknown
                            }
                        }
                        log.debug("Intervention {} - Found effectiveTime: startDate={}, endDate={} (nullFlavor handled)", 
                                validInterventions, startDate, endDate);
                    }
                    
                    // If no effectiveTime, try to get date from author/time (common for intervention orders)
                    if ((startDate == null || startDate.isEmpty()) && effectiveTime == null) {
                        NodeList authorNodes = act.getElementsByTagNameNS("*", "author");
                        if (authorNodes.getLength() == 0) {
                            authorNodes = act.getElementsByTagName("author");
                        }
                        
                        if (authorNodes.getLength() > 0) {
                            Element author = (Element) authorNodes.item(0);
                            NodeList timeNodes = author.getElementsByTagNameNS("*", "time");
                            if (timeNodes.getLength() == 0) {
                                timeNodes = author.getElementsByTagName("time");
                            }
                            
                            if (timeNodes.getLength() > 0) {
                                Element timeElement = (Element) timeNodes.item(0);
                                startDate = timeElement.getAttribute("value");
                                log.info("Intervention {} - Found author/time date: {}", validInterventions, startDate);
                            }
                        }
                    }
                    
                    // Set start date
                    if (startDate != null && !startDate.isEmpty()) {
                        try {
                            String isoDateTime = convertQrdaDateTimeToIso(startDate);
                            snomedCode.setStartDate(isoDateTime);
                            log.debug("Intervention {} - Set startDate: {} -> {}", validInterventions, startDate, isoDateTime);
                        } catch (Exception e) {
                            log.warn("Failed to parse intervention start date: {}", startDate, e);
                            snomedCode.setStartDate("");
                        }
                    } else {
                        snomedCode.setStartDate("");
                        log.debug("Intervention {} - No start date found", validInterventions);
                    }
                    
                    // Set end date
                    if (endDate != null && !endDate.isEmpty()) {
                        try {
                            String isoDateTime = convertQrdaDateTimeToIso(endDate);
                            snomedCode.setEndDate(isoDateTime);
                            log.debug("Intervention {} - Set endDate: {} -> {}", validInterventions, endDate, isoDateTime);
                        } catch (Exception e) {
                            log.warn("Failed to parse intervention end date: {}", endDate, e);
                            // Don't set endDate if parsing fails - leave as null to indicate unknown
                        }
                    } else {
                        snomedCode.setEndDate(null);
                    }
                    
                    snomedCodes.add(snomedCode);
                    log.info("Added SNOMED code to intervention: SCT-{}, description: {}", cleanCode, description);
                } else {
                    log.warn("Intervention {} has non-SNOMED codeSystem: {} (code: {}). Skipping code.", 
                            validInterventions, codeSystem, code);
                }
            } else {
                log.warn("Intervention {} has no code. Skipping.", validInterventions);
            }
            
            // Set as Object (can be List<SnomedCode> or empty string)
            if (snomedCodes.isEmpty()) {
                codeSection.setSnomedCodes("");
            } else {
                codeSection.setSnomedCodes(snomedCodes);
            }
            codeSection.setLoincCodes("");
            
            // Use intervention ID as key, or generate one if missing
            String key = (id != null && !id.isEmpty()) ? id : "intervention_" + validInterventions;
            interventionMap.put(key, codeSection);
            
            log.info("Intervention {} extracted: key={}, code={}, codeSystem={}, status={}, moodCode={}, description={}, snomedCodes={}", 
                    validInterventions, key, code, codeSystem, status, moodCode, description, snomedCodes.size());
        }
        
        log.info("=== INTERVENTION PARSING COMPLETE ===");
        log.info("Total acts found: {}, Skipped: {}, Valid interventions parsed: {}, Intervention entries created: {}", 
                actNodes.getLength(), skippedCount, validInterventions, interventionMap.size());
        
        if (interventionMap.isEmpty()) {
            log.warn("WARNING: No interventions were extracted from QRDA XML!");
            log.warn("This could mean: 1) No acts with classCode='ACT' and template IDs 2.16.840.1.113883.10.20.24.3.31 or 3.32 found, " +
                    "2) Acts found but no matching codeSystem, or 3) XML structure is different than expected");
        } else {
            log.info("Successfully extracted {} intervention entry(ies) from QRDA XML", interventionMap.size());
        }
    }
    
    /**
     * Convert QRDA datetime format (yyyyMMddHHmmss) to ISO datetime format (yyyy-MM-dd'T'HH:mm:ss.SSS'Z')
     */
    private String convertQrdaDateTimeToIso(String qrdaDateTime) {
        if (qrdaDateTime == null || qrdaDateTime.isEmpty()) {
            return "";
        }
        
        try {
            // Handle different QRDA datetime formats
            if (qrdaDateTime.length() == 14) {
                // yyyyMMddHHmmss
                LocalDateTime ldt = LocalDateTime.parse(qrdaDateTime,
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                return ldt.atZone(java.time.ZoneId.systemDefault())
                    .withZoneSameInstant(java.time.ZoneId.of("UTC"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
            } else if (qrdaDateTime.length() == 8) {
                // yyyyMMdd
                java.time.LocalDate ld = java.time.LocalDate.parse(qrdaDateTime, 
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
                return ld.atStartOfDay(java.time.ZoneId.systemDefault())
                    .withZoneSameInstant(java.time.ZoneId.of("UTC"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
            } else if (qrdaDateTime.matches("\\d+")) {
                // Epoch timestamp (seconds)
                long epochSeconds = Long.parseLong(qrdaDateTime);
                return java.time.Instant.ofEpochSecond(epochSeconds)
                    .atZone(java.time.ZoneId.of("UTC"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
            }
        } catch (Exception e) {
            log.warn("Failed to convert QRDA datetime to ISO: {}", qrdaDateTime, e);
        }
        
        return "";
    }

    /**
     * Check if element is a descendant of parent element
     */
    private boolean isDescendantOf(Element child, Element parent) {
        Node current = child.getParentNode();
        while (current != null && current.getNodeType() == Node.ELEMENT_NODE) {
            if (current.equals(parent)) {
                return true;
            }
            current = current.getParentNode();
        }
        return false;
    }

    /**
     * Parse datetime string to LocalDateTime
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) {
            return null;
        }

        try {
            if (dateTimeStr.length() == 8) {
                // yyyyMMdd
                int year = Integer.parseInt(dateTimeStr.substring(0, 4));
                int month = Integer.parseInt(dateTimeStr.substring(4, 6));
                int day = Integer.parseInt(dateTimeStr.substring(6, 8));
                return LocalDateTime.of(year, month, day, 0, 0);
            } else if (dateTimeStr.length() >= 14) {
                // yyyyMMddHHmmss
                int year = Integer.parseInt(dateTimeStr.substring(0, 4));
                int month = Integer.parseInt(dateTimeStr.substring(4, 6));
                int day = Integer.parseInt(dateTimeStr.substring(6, 8));
                int hour = dateTimeStr.length() > 8 ? Integer.parseInt(dateTimeStr.substring(8, 10)) : 0;
                int min = dateTimeStr.length() > 10 ? Integer.parseInt(dateTimeStr.substring(10, 12)) : 0;
                int sec = dateTimeStr.length() > 12 ? Integer.parseInt(dateTimeStr.substring(12, 14)) : 0;
                return LocalDateTime.of(year, month, day, hour, min, sec);
            }
        } catch (Exception e) {
            log.debug("Error parsing datetime: {}", dateTimeStr, e);
        }
        return null;
    }

    private void parseHospiceEncountersIntoFormResponse(Document doc, Map<String, CodeSection> interventionMap) {
        log.info("=== PARSING HOSPICE ENCOUNTERS INTO INTERVENTIONS ===");
        
        NodeList encounterNodes = doc.getElementsByTagName("encounter");
        int hospiceCount = 0;
        
        for (int i = 0; i < encounterNodes.getLength(); i++) {
            Element encounter = (Element) encounterNodes.item(i);
            String classCode = encounter.getAttribute("classCode");
            
            if (!"ENC".equals(classCode)) {
                continue;
            }
            
            String code = getAttr(encounter, "code", "code");
            String codeSystem = getAttr(encounter, "code", "codeSystem");
            String description = getText(encounter, "text");
            
            // Check if this is a hospice encounter
            if (isHospiceEncounter(code, codeSystem, description)) {
                hospiceCount++;
                
                String id = getAttr(encounter, "id", "extension");
                String status = getAttr(encounter, "statusCode", "code");
                
                log.info("Processing hospice encounter {}: id={}, code={}, codeSystem={}, status={}, description={}", 
                        hospiceCount, id, code, codeSystem, status, description);
                
                // Create CodeSection with SNOMED code
                CodeSection codeSection = new CodeSection();
                List<SnomedCode> snomedCodes = new ArrayList<>();
                
                // Create SNOMED code for hospice encounter
                SnomedCode snomedCode = new SnomedCode();
                snomedCode.setCode("SCT-183919006"); // SNOMED code for Hospice Encounter
                snomedCode.setConceptId("SCT-183919006");
                snomedCode.setDescription(description != null && !description.isEmpty() ? description : "Hospice Encounter");
                snomedCode.setTerm(description != null && !description.isEmpty() ? description : "Hospice Encounter");
                snomedCode.setStatus(status != null && !status.isEmpty() ? status : "completed");
                
                // Parse effectiveTime
                Element effectiveTime = (Element) encounter.getElementsByTagName("effectiveTime").item(0);
                if (effectiveTime != null) {
                    Element lowEl = (Element) effectiveTime.getElementsByTagNameNS("*", "low").item(0);
                    if (lowEl == null) {
                        lowEl = (Element) effectiveTime.getElementsByTagName("low").item(0);
                    }
                    String startDate = null;
                    if (lowEl != null) {
                        startDate = lowEl.getAttribute("value");
                        if ((startDate == null || startDate.isEmpty()) && lowEl.hasAttribute("nullFlavor")) {
                            startDate = null;
                        }
                    }
                    
                    Element highEl = (Element) effectiveTime.getElementsByTagNameNS("*", "high").item(0);
                    if (highEl == null) {
                        highEl = (Element) effectiveTime.getElementsByTagName("high").item(0);
                    }
                    String endDate = null;
                    if (highEl != null) {
                        endDate = highEl.getAttribute("value");
                        if ((endDate == null || endDate.isEmpty()) && highEl.hasAttribute("nullFlavor")) {
                            endDate = null; // Leave as null to indicate ongoing/unknown
                        }
                    }
                    
                    if (startDate != null && !startDate.isEmpty()) {
                        try {
                            String isoDateTime = convertQrdaDateTimeToIso(startDate);
                            snomedCode.setStartDate(isoDateTime);
                        } catch (Exception e) {
                            log.warn("Failed to parse hospice encounter start date: {}", startDate, e);
                            snomedCode.setStartDate("");
                        }
                    } else {
                        snomedCode.setStartDate("");
                    }
                    
                    if (endDate != null && !endDate.isEmpty()) {
                        try {
                            String isoDateTime = convertQrdaDateTimeToIso(endDate);
                            snomedCode.setEndDate(isoDateTime);
                        } catch (Exception e) {
                            log.warn("Failed to parse hospice encounter end date: {}", endDate, e);
                            // Don't set endDate if parsing fails - leave as null to indicate unknown
                        }
                    } else {
                        snomedCode.setEndDate(null);
                    }
                } else {
                    snomedCode.setStartDate("");
                    snomedCode.setEndDate(null);
                }
                
                snomedCodes.add(snomedCode);
                codeSection.setSnomedCodes(snomedCodes);
                codeSection.setLoincCodes("");
                
                // Use encounter ID as key, or generate one if missing
                String key = (id != null && !id.isEmpty()) ? "hospice_" + id : "hospice_" + hospiceCount;
                interventionMap.put(key, codeSection);
                
                log.info("Hospice encounter {} extracted as intervention: key={}, code=SCT-183919006, status={}, description={}", 
                        hospiceCount, key, status, description);
            }
        }
        
        log.info("=== HOSPICE ENCOUNTER PARSING COMPLETE ===");
        log.info("Total hospice encounters found and added as interventions: {}", hospiceCount);
    }

    private boolean isHospiceEncounter(String code, String codeSystem, String description) {
        // Hospice encounter SNOMED CT code
        // Primary code: 183919006 = Hospice Encounter
        if (code != null && !code.isBlank() && 
            codeSystem != null && codeSystem.contains("2.16.840.1.113883.6.96")) {
            if ("183919006".equals(code)) {
                log.debug("Hospice encounter detected by code: {} (SNOMED CT)", code);
                return true;
            }
        }
        
        // Check description for hospice keywords
        if (description != null && !description.isBlank()) {
            String upperDesc = description.toUpperCase();
            if (upperDesc.contains("HOSPICE ENCOUNTER") || 
                upperDesc.contains("HOSPICE")) {
                log.debug("Hospice encounter detected by description: {}", description);
                return true;
            }
        }
        
        return false;
    }

    private void parseHospiceObservationsIntoFormResponse(Document doc, Map<String, CodeSection> interventionMap) {
        log.info("=== PARSING HOSPICE OBSERVATIONS (VALUE ELEMENTS) INTO INTERVENTIONS ===");
        
        // All hospice codes that should be extracted
        Set<String> hospiceCodes = Set.of(
            "183919006",  // Hospice care (regime/therapy)
            "305336008",  // Hospice care (procedure)
            "385763009",  // Palliative care (often used with hospice)
            "456661000124102",
            "170935008"   // Palliative care procedure
        );
        
        NodeList obsNodes = doc.getElementsByTagNameNS("*", "observation");
        if (obsNodes.getLength() == 0) {
            obsNodes = doc.getElementsByTagName("observation");
        }
        
        int hospiceObservationCount = 0;
        
        for (int i = 0; i < obsNodes.getLength(); i++) {
            Element obs = (Element) obsNodes.item(i);
            
            // Check for value elements with hospice codes
            NodeList valueNodes = obs.getElementsByTagNameNS("*", "value");
            if (valueNodes.getLength() == 0) {
                valueNodes = obs.getElementsByTagName("value");
            }
            
            for (int j = 0; j < valueNodes.getLength(); j++) {
                Element valueEl = (Element) valueNodes.item(j);
                String valueCode = valueEl.getAttribute("code");
                String valueCodeSystem = valueEl.getAttribute("codeSystem");
                
                // Check if this is a hospice code
                if (valueCode != null && !valueCode.isBlank() && 
                    valueCodeSystem != null && valueCodeSystem.contains("2.16.840.1.113883.6.96") &&
                    hospiceCodes.contains(valueCode)) {
                    
                    hospiceObservationCount++;
                    
                    String id = getAttr(obs, "id", "extension");
                    if (id == null || id.isEmpty()) {
                        id = getAttr(obs, "id", "root");
                    }
                    String status = getAttr(obs, "statusCode", "code");
                    String description = valueEl.getAttribute("displayName");
                    if (description == null || description.isEmpty()) {
                        description = getText(valueEl, "originalText");
                    }
                    if (description == null || description.isEmpty()) {
                        description = "Hospice Care"; // Default description
                    }
                    
                    log.info("Processing hospice observation {}: id={}, valueCode={}, codeSystem={}, status={}, description={}", 
                            hospiceObservationCount, id, valueCode, valueCodeSystem, status, description);
                    
                    // Create CodeSection with SNOMED code
                    CodeSection codeSection = new CodeSection();
                    List<SnomedCode> snomedCodes = new ArrayList<>();
                    
                    // Create SNOMED code for hospice observation
                    SnomedCode snomedCode = new SnomedCode();
                    snomedCode.setCode("SCT-" + valueCode);
                    snomedCode.setConceptId("SCT-" + valueCode);
                    snomedCode.setDescription(description);
                    snomedCode.setTerm(description);
                    snomedCode.setStatus(status != null && !status.isEmpty() ? status : "completed");
                    
                    Element effectiveTime = (Element) obs.getElementsByTagNameNS("*", "effectiveTime").item(0);
                    if (effectiveTime == null) {
                        effectiveTime = (Element) obs.getElementsByTagName("effectiveTime").item(0);
                    }
                    
                    if (effectiveTime == null) {
                        Node currentNode = obs.getParentNode();
                        while (currentNode != null) {
                            if (currentNode.getNodeType() == Node.ELEMENT_NODE) {
                                Element element = (Element) currentNode;
                                String tagName = element.getTagName();
                                // Remove namespace prefix if present
                                if (tagName.contains(":")) {
                                    tagName = tagName.substring(tagName.indexOf(":") + 1);
                                }
                                
                                // Check if this is an act element
                                if ("act".equalsIgnoreCase(tagName)) {
                                    effectiveTime = (Element) element.getElementsByTagNameNS("*", "effectiveTime").item(0);
                                    if (effectiveTime == null) {
                                        effectiveTime = (Element) element.getElementsByTagName("effectiveTime").item(0);
                                    }
                                    if (effectiveTime != null) {
                                        log.debug("Found effectiveTime on parent act element for hospice observation");
                                        break;
                                    }
                                }
                            }
                            currentNode = currentNode.getParentNode();
                        }
                    }
                    
                    if (effectiveTime != null) {
                        // Get low element
                        Element lowEl = (Element) effectiveTime.getElementsByTagNameNS("*", "low").item(0);
                        if (lowEl == null) {
                            lowEl = (Element) effectiveTime.getElementsByTagName("low").item(0);
                        }
                        String startDate = null;
                        if (lowEl != null) {
                            startDate = lowEl.getAttribute("value");
                            // Check for nullFlavor
                            if ((startDate == null || startDate.isEmpty()) && lowEl.hasAttribute("nullFlavor")) {
                                startDate = null; // Don't set date if nullFlavor is present
                            }
                        }
                        
                        // Get high element
                        Element highEl = (Element) effectiveTime.getElementsByTagNameNS("*", "high").item(0);
                        if (highEl == null) {
                            highEl = (Element) effectiveTime.getElementsByTagName("high").item(0);
                        }
                        String endDate = null;
                        if (highEl != null) {
                            endDate = highEl.getAttribute("value");
                            // Check for nullFlavor (like nullFlavor='UNK')
                            if ((endDate == null || endDate.isEmpty()) && highEl.hasAttribute("nullFlavor")) {
                                endDate = null; // Don't set date if nullFlavor is present - means ongoing/unknown
                            }
                        }
                        
                        if (startDate != null && !startDate.isEmpty()) {
                            try {
                                String isoDateTime = convertQrdaDateTimeToIso(startDate);
                                snomedCode.setStartDate(isoDateTime);
                            } catch (Exception e) {
                                log.warn("Failed to parse hospice observation start date: {}", startDate, e);
                                snomedCode.setStartDate("");
                            }
                        } else {
                            snomedCode.setStartDate("");
                        }
                        
                        if (endDate != null && !endDate.isEmpty()) {
                            try {
                                String isoDateTime = convertQrdaDateTimeToIso(endDate);
                                snomedCode.setEndDate(isoDateTime);
                            } catch (Exception e) {
                                log.warn("Failed to parse hospice observation end date: {}", endDate, e);
                            }
                        } else {
                            snomedCode.setEndDate(null);
                        }
                    } else {
                        snomedCode.setStartDate("");
                        snomedCode.setEndDate("");
                    }
                    
                    snomedCodes.add(snomedCode);
                    codeSection.setSnomedCodes(snomedCodes);
                    codeSection.setLoincCodes("");
                    
                    // Use observation ID as key, or generate one if missing
                    String key = (id != null && !id.isEmpty()) ? "hospice_obs_" + id : "hospice_obs_" + hospiceObservationCount;
                    interventionMap.put(key, codeSection);
                    
                    log.info("Hospice observation {} extracted as intervention: key={}, code=SCT-{}, status={}, description={}", 
                            hospiceObservationCount, key, valueCode, status, description);
                }
            }
        }
        
        log.info("=== HOSPICE OBSERVATION PARSING COMPLETE ===");
        log.info("Total hospice observations found and added as interventions: {}", hospiceObservationCount);
    }

    /**
     * Check if encounter is an inpatient encounter (care setting, not a visit/appointment)
     * Inpatient encounters should NOT be uploaded to EHR as appointments
     * 
     * Example from QRDA:
     * <code code="32485007" codeSystem="2.16.840.1.113883.6.96" codeSystemName="SNOMEDCT"/>
     * <text>Encounter Inpatient</text>
     * 
     * @param code Encounter code (e.g., "32485007" for inpatient encounter)
     * @param codeSystem Code system (e.g., "2.16.840.1.113883.6.96" for SNOMED CT)
     * @param description Encounter description (e.g., "Encounter Inpatient")
     * @return true if this is an inpatient encounter
     */
    private boolean isInpatientEncounter(String code, String codeSystem, String description) {
        // Inpatient encounter SNOMED CT codes
        // Primary code: 32485007 = Encounter Inpatient
        Set<String> inpatientCodes = Set.of(
                "32485007",   // Encounter Inpatient (primary code from user's example)
                "308335008",  // Inpatient encounter
                "390906007",  // Inpatient stay
                "305351004",  // Inpatient hospital encounter
                "183460006"   // Hospital inpatient encounter
        );
        
        // Check code if it's SNOMED CT (codeSystem contains SNOMED CT OID)
        if (code != null && !code.isBlank() && 
            codeSystem != null && codeSystem.contains("2.16.840.1.113883.6.96")) {
            if (inpatientCodes.contains(code)) {
                log.debug("Inpatient encounter detected by code: {} (SNOMED CT)", code);
                return true;
            }
        }
        
        // Check description for inpatient keywords
        if (description != null && !description.isBlank()) {
            String upperDesc = description.toUpperCase();
            if (upperDesc.contains("ENCOUNTER INPATIENT") || 
                upperDesc.contains("INPATIENT") || 
                upperDesc.contains("IN-PATIENT") ||
                upperDesc.contains("HOSPITAL ADMISSION") || 
                upperDesc.contains("HOSPITAL STAY") ||
                upperDesc.contains("INPATIENT ENCOUNTER")) {
                log.debug("Inpatient encounter detected by description: {}", description);
                return true;
            }
        }
        
        return false;
    }

    private boolean isEmergencyDepartmentVisit(String code, String codeSystem, String description) {
        // Emergency Department Visit SNOMED CT code
        // Primary code: 4525004 = Emergency Department Visit
        String emergencyDeptCode = "4525004";
        
        // Check code if it's SNOMED CT (codeSystem contains SNOMED CT OID)
        if (code != null && !code.isBlank() && 
            codeSystem != null && codeSystem.contains("2.16.840.1.113883.6.96")) {
            if (emergencyDeptCode.equals(code)) {
                log.debug("Emergency Department Visit encounter detected by code: {} (SNOMED CT)", code);
                return true;
            }
        }
        
        if (description != null && !description.isBlank()) {
            String upperDesc = description.toUpperCase();
            if (upperDesc.contains("EMERGENCY DEPARTMENT VISIT") || 
                upperDesc.contains("EMERGENCY DEPARTMENT") ||
                upperDesc.contains("ED VISIT") ||
                upperDesc.contains("ER VISIT")) {
                log.debug("Emergency Department Visit encounter detected by description: {}", description);
                return true;
            }
        }
        
        return false;
    }


    @Override
    public ExtractedProviderDetails extractProviderDetails(InputStream xmlInput) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(xmlInput);
        doc.getDocumentElement().normalize();

        DoctorDetailsData providerDetails = parseProviderDetailsFromDoc(doc);
        return new QRDAExtractionService.ExtractedProviderDetails(providerDetails);
    }


    private void extractProviderFromAssignedEntity(Element assignedEntity, DoctorDetailsData providerDetails, Document doc) {
        // Extract NPI (root: 2.16.840.1.113883.4.6)
        NodeList idNodes = assignedEntity.getElementsByTagName("id");
        for (int i = 0; i < idNodes.getLength(); i++) {
            Element idEl = (Element) idNodes.item(i);
            String root = idEl.getAttribute("root");
            String extension = idEl.getAttribute("extension");
            
            if ("2.16.840.1.113883.4.6".equals(root) && extension != null && !extension.isEmpty()) {
                providerDetails.setNpi(extension);
            } else if ("2.16.840.1.113883.4.336".equals(root) && extension != null && !extension.isEmpty()) {
                // CCN (CMS Certificate Number)
                providerDetails.setCms_certificate_number(extension);
            }
        }

        // Extract provider name from assignedPerson
        NodeList personNodes = assignedEntity.getElementsByTagName("assignedPerson");
        if (personNodes.getLength() > 0) {
            Element person = (Element) personNodes.item(0);
            NodeList nameNodes = person.getElementsByTagName("name");
            if (nameNodes.getLength() > 0) {
                Element nameEl = (Element) nameNodes.item(0);
                String given = getText(nameEl, "given");
                String family = getText(nameEl, "family");
                if (given != null && !given.isEmpty()) {
                    String[] nameParts = given.split("\\s+");
                    providerDetails.setFirst_name(nameParts[0]);
                    if (nameParts.length > 1) {
                        providerDetails.setMiddle_name(nameParts[1]);
                    }
                }
                if (family != null && !family.isEmpty()) {
                    providerDetails.setLast_name(family);
                }
            }
        }

        // Extract email and phone from telecom
        NodeList telecomNodes = assignedEntity.getElementsByTagName("telecom");
        for (int i = 0; i < telecomNodes.getLength(); i++) {
            Element telecom = (Element) telecomNodes.item(i);
            String value = telecom.getAttribute("value");
            if (value != null) {
                if (value.startsWith("mailto:")) {
                    providerDetails.setEmail(value.substring(7));
                } else if (value.startsWith("tel:")) {
                    providerDetails.setMobile(value.substring(4));
                }
            }
        }

        // Extract TIN from representedOrganization
        NodeList orgNodes = assignedEntity.getElementsByTagName("representedOrganization");
        for (int i = 0; i < orgNodes.getLength(); i++) {
            Element org = (Element) orgNodes.item(i);
            NodeList orgIdNodes = org.getElementsByTagName("id");
            for (int j = 0; j < orgIdNodes.getLength(); j++) {
                Element orgIdEl = (Element) orgIdNodes.item(j);
                String root = orgIdEl.getAttribute("root");
                String extension = orgIdEl.getAttribute("extension");
                // TIN is typically in root 2.16.840.1.113883.4.2
                if ("2.16.840.1.113883.4.2".equals(root) && extension != null && !extension.isEmpty()) {
                    providerDetails.setTax_id_number(extension);
                }
            }
        }

    }

}
