package com.onc.QRDA.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onc.EHR.dto.*;
import com.onc.EHR.service.EHRDataService;
import com.onc.QRDA.service.QRDACMSService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openhealthtools.mdht.uml.cda.*;
import org.openhealthtools.mdht.uml.cda.util.CDAUtil;
import org.openhealthtools.mdht.uml.hl7.datatypes.*;
import org.openhealthtools.mdht.uml.hl7.vocab.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class QRDACMSServiceImpl implements QRDACMSService {

    @Value("${fhir.base-url}")
    private String baseUrl;

    // Vendor identity stamped into the generated QRDA XML. Deployment-specific, so it is
    // configuration rather than a hardcoded name. Defaults reproduce the previous output exactly.
    @Value("${qrda.vendor.manufacturer-model-name:EHR }")
    private String manufacturerModelNameValue;

    @Value("${qrda.vendor.software-name:EHR}")
    private String softwareNameValue;

    @Value("${qrda.custodian.organization-name:EHR Test Deck}")
    private String custodianOrganizationName;

    @Value("${qrda.legal-authenticator.organization-name:EHR}")
    private String legalAuthenticatorOrganizationName;

    private final ObjectMapper objectMapper;
    private final EHRDataService ehrDataService;


    @Override
    public ResponseEntity<byte[]> getQrda(String fhirId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        ClinicalDocument clinicalDocument = CDAFactory.eINSTANCE.createClinicalDocument();
        clinicalDocument = createQrdaDocument(clinicalDocument, fhirId);
        String xmlString = generateXmlWithValueSetFix(clinicalDocument);
        byte[] xmlBytes = xmlString.getBytes(StandardCharsets.UTF_8);

        return new ResponseEntity<>(xmlBytes, headers, HttpStatus.OK);
    }

    // ---------------------------------------------------------------------------
    // EHR reads. Thin pass-throughs to the shared EHRDataService so QRDA and G2 share
    // one implementation; the endpoints this exposes are unchanged.
    // ---------------------------------------------------------------------------

    @Override
    public MedicalDetailsData fetchPatientMedicalDetails(String fhirId) {
        return ehrDataService.fetchPatientMedicalDetails(fhirId);
    }

    @Override
    public PersonalDetailsData fetchPatientPersonalDetails(String fhirId) {
        return ehrDataService.fetchPatientPersonalDetails(fhirId);
    }

    @Override
    public List<InsuranceDetails> fetchPatientInsuranceDetails(String fhirId) {
        return ehrDataService.fetchPatientInsuranceDetails(fhirId);
    }

    @Override
    public DoctorDetailsData fetchDoctorDetails(int doctorId) {
        return ehrDataService.fetchDoctorDetails(doctorId);
    }

    @Override
    public List<FormData> fetchSoapDetails(String fhirId) {
        return ehrDataService.fetchSoapDetails(fhirId);
    }

    @Override
    public AppointmentData fetchAppointments(String fhirId, String clinicId) {
        return ehrDataService.fetchAppointments(fhirId, clinicId);
    }

    /**
     * The author, custodian and legal-authenticator blocks all need the creating doctor and all
     * treat an unavailable one as "omit the detail", so a failed lookup is swallowed here rather
     * than at each of the four call sites.
     */
    private DoctorDetailsData doctorOrNull(Integer doctorId) {
        if (doctorId == null || doctorId <= 0) {
            return null;
        }
        try {
            return fetchDoctorDetails(doctorId);
        } catch (Exception e) {
            log.warn("Provider {} could not be read; its details are omitted from the document", doctorId);
            return null;
        }
    }

    public String extractPatientId(String patientFhirId) {
        return ehrDataService.extractPatientId(patientFhirId);
    }

    /**
     * First populated entry of a multi-valued demographic.
     *
     * <p>Race, ethnicity and preferred language are lists because a patient may report more
     * than one. CDA carries a single {@code raceCode}/{@code ethnicGroupCode}, so the first
     * value is the one written; for the single-valued data this previously read, the result
     * is identical.
     */
    private String firstValue(List<String> values) {
        if (CollectionUtils.isEmpty(values)) {
            return null;
        }
        return values.stream().filter(StringUtils::hasText).findFirst().orElse(null);
    }


    @Override
    public String getQrdaXml(String fhirId) {
        ClinicalDocument clinicalDocument = CDAFactory.eINSTANCE.createClinicalDocument();
        clinicalDocument = createQrdaDocument(clinicalDocument, fhirId);

        return generateXmlWithValueSetFix(clinicalDocument);
    }


    private PersonalDetailsData getPersonalDetailsData(String fhirId) {
        try {
            return fetchPatientPersonalDetails(fhirId);
        } catch (Exception e) {
            log.warn("Personal details unavailable for {}", fhirId);
            return null;
        }
    }

    private List<Appointment> getAppointments(String fhirId) {
        try {
            AppointmentData data = fetchAppointments(fhirId, null);
            if (data != null && data.getAppointments() != null) {
                return data.getAppointments();
            }
        } catch (Exception e) {
            log.warn("Appointments unavailable for {}", fhirId);
        }
        return Collections.emptyList();
    }

    @Override
    public ResponseEntity<?> generateQrdaZip(List<String> fhirIds) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (String singlePatientId : fhirIds) {
                PersonalDetailsData patientData = getPersonalDetailsData(singlePatientId);
                List<Appointment> appointments = getAppointments(singlePatientId);
                String measurementPeriodStart = "2023-01-01";
                String measurementPeriodEnd = "2025-12-31";

                if (isInInitialPopulation(patientData, appointments, measurementPeriodStart, measurementPeriodEnd)) {
                    String firstName = null;
                    String lastName = null;

                    if (patientData != null && patientData.getResponse() != null && !CollectionUtils.isEmpty(patientData.getResponse().getPatientInformation())) {
                        Map<String, PatientInformation> patientInfoMap = patientData.getResponse().getPatientInformation();
                        PatientInformation patientInfo = patientInfoMap.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
                        if (patientInfo != null) {
                            if (StringUtils.hasText(patientInfo.getFirstName())) {
                                firstName = patientInfo.getFirstName().trim();
                            }
                            if (StringUtils.hasText(patientInfo.getLastName())) {
                                lastName = patientInfo.getLastName().trim();
                            }
                        }
                    }

                    String fileName = firstName + "_" + lastName + ".xml";
                    String xmlContent = getQrdaXml(singlePatientId);
                    ZipEntry entry = new ZipEntry(fileName);
                    zos.putNextEntry(entry);
                    zos.write(xmlContent.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=CMS139.zip")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(baos.toByteArray());
    }

    public ClinicalDocument createQrdaDocument(ClinicalDocument document, String fhirId) {
        // Each read degrades on its own: an unavailable section is left out rather than failing
        // the document. That the result is then clinically incomplete is not signalled anywhere.
        PersonalDetailsData patientData = getPersonalDetailsData(fhirId);

        List<InsuranceDetails> insuranceDetails = new ArrayList<>();
        try {
            List<InsuranceDetails> details = fetchPatientInsuranceDetails(fhirId);
            if (details != null) {
                insuranceDetails = details;
            }
        } catch (Exception e) {
            log.warn("Insurance details unavailable for {}", fhirId);
        }

        List<Appointment> appointments = getAppointments(fhirId);


        CS realmCode = DatatypesFactory.eINSTANCE.createCS("US");
        document.getRealmCodes().add(realmCode);

        InfrastructureRootTypeId IITypeId = CDAFactory.eINSTANCE.createInfrastructureRootTypeId();
        IITypeId.setRoot("2.16.840.1.113883.1.3");
        IITypeId.setExtension("POCD_HD000040");
        document.setTypeId(IITypeId);

        II usRealmHeader = DatatypesFactory.eINSTANCE.createII();
        usRealmHeader.setRoot("2.16.840.1.113883.10.20.22.1.1");
        usRealmHeader.setExtension("2015-08-01");
        document.getTemplateIds().add(usRealmHeader);

        II qrdaTemplateId = DatatypesFactory.eINSTANCE.createII();
        qrdaTemplateId.setRoot("2.16.840.1.113883.10.20.24.1.1");
        qrdaTemplateId.setExtension("2017-08-01");
        document.getTemplateIds().add(qrdaTemplateId);

        II qdmBasedQrdaTemplateId = DatatypesFactory.eINSTANCE.createII();
        qdmBasedQrdaTemplateId.setRoot("2.16.840.1.113883.10.20.24.1.2");
        qdmBasedQrdaTemplateId.setExtension("2021-08-01");
        document.getTemplateIds().add(qdmBasedQrdaTemplateId);

        II cmsQrdaTemplateId = DatatypesFactory.eINSTANCE.createII();
        cmsQrdaTemplateId.setRoot("2.16.840.1.113883.10.20.24.1.3");
        cmsQrdaTemplateId.setExtension("2022-02-01");
        document.getTemplateIds().add(cmsQrdaTemplateId);

        II IIId = DatatypesFactory.eINSTANCE.createII();
        UUID guidQRDA = UUID.randomUUID();
        IIId.setRoot(guidQRDA.toString());
        document.setId(IIId);

        CE CECode = DatatypesFactory.eINSTANCE.createCE();
        CECode.setCode("55182-0");
        CECode.setCodeSystem("2.16.840.1.113883.6.1");
        CECode.setCodeSystemName("LOINC");
        CECode.setDisplayName("Quality Measure Report");
        document.setCode(CECode);

        ST STTitle = DatatypesFactory.eINSTANCE.createST();
        STTitle.addText("QRDA Incidence Report");
        document.setTitle(STTitle);

        TS TSEffectiveTime = DatatypesFactory.eINSTANCE.createTS();
        TSEffectiveTime.setValue(getCurrentTimestamp());
        document.setEffectiveTime(TSEffectiveTime);

        CE CEConfidentialityCode = DatatypesFactory.eINSTANCE.createCE();
        CEConfidentialityCode.setCode("N");
        CEConfidentialityCode.setCodeSystem("2.16.840.1.113883.5.25");
        document.setConfidentialityCode(CEConfidentialityCode);

        CS CSLanguageCode = DatatypesFactory.eINSTANCE.createCS();
        CSLanguageCode.setCode("en");
        document.setLanguageCode(CSLanguageCode);

        Component2 component = CDAFactory.eINSTANCE.createComponent2();
        StructuredBody qrdaStructuredBody = CDAFactory.eINSTANCE.createStructuredBody();
        component.setStructuredBody(qrdaStructuredBody);
        document.setComponent(component);

        addRecordTarget(document, patientData);
        addAuthor(document, patientData);
        addCustodian(document, patientData);
        addLegalAuthenticator(document, patientData);
        addDocumentationOf(document, patientData);
        addMeasureSection(qrdaStructuredBody);
        addReportingParametersSection(qrdaStructuredBody);
        addPatientDataSection(qrdaStructuredBody, patientData, insuranceDetails, appointments);

        return document;
    }

    private void addRecordTarget(ClinicalDocument document, PersonalDetailsData patientData) {
        PatientRole patientRole = CDAFactory.eINSTANCE.createPatientRole();
        II IIPatientRoleId = DatatypesFactory.eINSTANCE.createII();
        IIPatientRoleId.setRoot("1.3.6.1.4.1.115");
        UUID patientRoleUUID = UUID.randomUUID();
        IIPatientRoleId.setExtension(patientRoleUUID.toString());
        patientRole.getIds().add(IIPatientRoleId);

        AD address = DatatypesFactory.eINSTANCE.createAD();
        address.getUses().add(PostalAddressUse.HP);

        if (Objects.nonNull(patientData) && Objects.nonNull(patientData.getResponse()) && Objects.nonNull(patientData.getResponse().getPatientInformation())) {
            Map<String, PatientInformation> patientInfoMap = patientData.getResponse().getPatientInformation();
            if (!patientInfoMap.isEmpty()) {
                // Get the first non-null patient information entry
                PatientInformation patientInfo = patientInfoMap.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
                if (Objects.nonNull(patientInfo)) {
                    address.addStreetAddressLine(StringUtils.hasText(patientInfo.getAddressLine1()) ? patientInfo.getAddressLine1() : "");
                    address.addCity(StringUtils.hasText(patientInfo.getCity()) ? patientInfo.getCity() : "");
                    address.addState(StringUtils.hasText(patientInfo.getState()) ? patientInfo.getState() : "");
                    address.addPostalCode(StringUtils.hasText(patientInfo.getZipCode()) ? patientInfo.getZipCode() : "");
                    address.addCountry("US");
                }
            }
        }
        patientRole.getAddrs().add(address);

        if (Objects.nonNull(patientData) && Objects.nonNull(patientData.getResponse()) && Objects.nonNull(patientData.getResponse().getPatientInformation())) {
            Map<String, PatientInformation> patientInfoMap = patientData.getResponse().getPatientInformation();
            if (!patientInfoMap.isEmpty()) {
                PatientInformation patientInfo = patientInfoMap.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
                if (Objects.nonNull(patientInfo) && Objects.nonNull(patientInfo.getPhone()) && !patientInfo.getPhone().isEmpty()) {
                    for (Phone phone : patientInfo.getPhone()) {
                        if (Objects.nonNull(phone) && StringUtils.hasText(phone.getContact())) {
                            TEL tel = DatatypesFactory.eINSTANCE.createTEL("tel:" + phone.getContact());
                            if (StringUtils.hasText(phone.getLabel())) {
                                if ("Home".equalsIgnoreCase(phone.getLabel()))
                                    tel.getUses().add(TelecommunicationAddressUse.HP);
                                if ("Work".equalsIgnoreCase(phone.getLabel()))
                                    tel.getUses().add(TelecommunicationAddressUse.WP);
                            }
                            patientRole.getTelecoms().add(tel);
                        }
                    }
                }

                if (StringUtils.hasText(patientInfo.getEmail())) {
                    TEL telEmail = DatatypesFactory.eINSTANCE.createTEL("mailto:" + patientInfo.getEmail());
                    telEmail.getUses().add(TelecommunicationAddressUse.HP);
                    patientRole.getTelecoms().add(telEmail);
                }
            }
        }


        org.openhealthtools.mdht.uml.cda.Patient patient = CDAFactory.eINSTANCE.createPatient();
        II IIPatientId = DatatypesFactory.eINSTANCE.createII();
        IIPatientId.setRoot("1.3.6.1.4.1.115");
        UUID IIPatientIdUUID = UUID.randomUUID();
        IIPatientId.setExtension(IIPatientIdUUID.toString());

        PN name = DatatypesFactory.eINSTANCE.createPN();
        if (Objects.nonNull(patientData) && Objects.nonNull(patientData.getResponse()) && Objects.nonNull(patientData.getResponse().getPatientInformation())) {
            Map<String, PatientInformation> patientInfoMap = patientData.getResponse().getPatientInformation();
            if (!patientInfoMap.isEmpty()) {
                PatientInformation patientInfo = patientInfoMap.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
                if (Objects.nonNull(patientInfo)) {
                    name.addGiven(StringUtils.hasText(patientInfo.getFirstName()) ? patientInfo.getFirstName() : "");
                    name.addFamily(StringUtils.hasText(patientInfo.getLastName()) ? patientInfo.getLastName() : "");
                }
            }
        }
        patient.getNames().add(name);


        CE CEAdministrativeGenderCode = DatatypesFactory.eINSTANCE.createCE();
        CEAdministrativeGenderCode.setCodeSystem("2.16.840.1.113883.5.1");
        CEAdministrativeGenderCode.setCodeSystemName("AdministrativeGender");
        if (Objects.nonNull(patientData) && Objects.nonNull(patientData.getResponse()) && !CollectionUtils.isEmpty(patientData.getResponse().getPatientInformation())) {
            Map<String, PatientInformation> patientInfoMap = patientData.getResponse().getPatientInformation();
            PatientInformation patientInfo = patientInfoMap.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
            if (Objects.nonNull(patientInfo) && StringUtils.hasText(patientInfo.getGender())) {
                String gender = patientInfo.getGender().toUpperCase();
                if ("MALE".equalsIgnoreCase(gender)) {
                    CEAdministrativeGenderCode.setCode("M");
                } else if ("FEMALE".equalsIgnoreCase(gender)) {
                    CEAdministrativeGenderCode.setCode("F");
                } else {
                    CEAdministrativeGenderCode.setCode("UN"); // default
                }
            }
        }

        patient.setAdministrativeGenderCode(CEAdministrativeGenderCode);

        if (Objects.nonNull(patientData) && Objects.nonNull(patientData.getResponse()) && !CollectionUtils.isEmpty(patientData.getResponse().getPatientInformation())) {
            Map<String, PatientInformation> patientInfoMap = patientData.getResponse().getPatientInformation();
            PatientInformation patientInfo = patientInfoMap.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
            if (Objects.nonNull(patientInfo) && StringUtils.hasText(patientInfo.getBirthDate())) {
                String actualBirthDate = patientInfo.getBirthDate();
                String formattedBirthDate = actualBirthDate.replaceAll("-", "");
                patient.setBirthTime(DatatypesFactory.eINSTANCE.createTS(formattedBirthDate));
            }
        }

        CE raceCode = DatatypesFactory.eINSTANCE.createCE();
        raceCode.setCodeSystem("2.16.840.1.113883.6.238");
        raceCode.setCodeSystemName("CDCREC");
        if (Objects.nonNull(patientData) && Objects.nonNull(patientData.getResponse()) && !CollectionUtils.isEmpty(patientData.getResponse().getPatientInformation())) {
            Map<String, PatientInformation> patientInfoMap = patientData.getResponse().getPatientInformation();
            PatientInformation patientInfo = patientInfoMap.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
            String rawRace = firstValue(Objects.nonNull(patientInfo) ? patientInfo.getRace() : null);
            if (StringUtils.hasText(rawRace)) {
                String race = rawRace.toLowerCase();
                switch (race) {
                    case "white":
                        raceCode.setCode("2106-3");
                        break;
                    case "black or african american":
                        raceCode.setCode("2054-5");
                        break;
                    case "asian":
                        raceCode.setCode("2028-9");
                        break;
                    case "american indian or alaska native":
                        raceCode.setCode("1002-5");
                        break;
                    case "native hawaiian or other pacific islander":
                        raceCode.setCode("2076-8");
                        break;
                    default:
                        raceCode.setCode("");
                        break;
                }
            }
        }

        patient.setRaceCode(raceCode);

        CE ethnicityCode = DatatypesFactory.eINSTANCE.createCE();
        ethnicityCode.setCodeSystem("2.16.840.1.113883.6.238");
        ethnicityCode.setCodeSystemName("CDCREC");

        if (Objects.nonNull(patientData) && Objects.nonNull(patientData.getResponse()) && Objects.nonNull(patientData.getResponse().getPatientInformation())) {
            Map<String, PatientInformation> patientInfoMap = patientData.getResponse().getPatientInformation();
            PatientInformation patientInfo = patientInfoMap.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
            String rawEthnicity = firstValue(Objects.nonNull(patientInfo) ? patientInfo.getEthnicity() : null);
            if (StringUtils.hasText(rawEthnicity)) {
                String ethnicity = rawEthnicity.trim();
                if (ethnicity.equalsIgnoreCase("hispanic") || ethnicity.equalsIgnoreCase("latino")  || ethnicity.equalsIgnoreCase("hispanic or latino") ) {
                    ethnicityCode.setCode("2135-2"); // Hispanic or Latino
                } else {
                    ethnicityCode.setCode("2186-5"); // Not Hispanic or Latino
                }
            }
        }

        patient.setEthnicGroupCode(ethnicityCode);


        LanguageCommunication langComm = CDAFactory.eINSTANCE.createLanguageCommunication();
        II hitspTemplateId = DatatypesFactory.eINSTANCE.createII();
        hitspTemplateId.setRoot("2.16.840.1.113883.3.88.11.83.2");
        hitspTemplateId.setAssigningAuthorityName("HITSP/C83");
        langComm.getTemplateIds().add(hitspTemplateId);

        II iheTemplateId = DatatypesFactory.eINSTANCE.createII();
        iheTemplateId.setRoot("1.3.6.1.4.1.19376.1.5.3.1.2.1");
        iheTemplateId.setAssigningAuthorityName("IHE/PCC");
        langComm.getTemplateIds().add(iheTemplateId);

        CS langCode = DatatypesFactory.eINSTANCE.createCS();
        langCode.setCode("eng");
        langComm.setLanguageCode(langCode);
        patient.getLanguageCommunications().add(langComm);

        patientRole.setPatient(patient);
        RecordTarget recordTarget = CDAFactory.eINSTANCE.createRecordTarget();
        recordTarget.setPatientRole(patientRole);
        document.getRecordTargets().add(recordTarget);
    }

    private void addAuthor(ClinicalDocument document, PersonalDetailsData patientData) {
        Author author = CDAFactory.eINSTANCE.createAuthor();
        author.setTime(DatatypesFactory.eINSTANCE.createTS(getCurrentTimestamp()));

        AssignedAuthor assignedAuthor = CDAFactory.eINSTANCE.createAssignedAuthor();

        int doctorId = Optional.ofNullable(patientData).map(PersonalDetailsData::getCreatedBy).orElse(null);

        DoctorDetailsData doctorDetails = doctorOrNull(doctorId);

        AD address = DatatypesFactory.eINSTANCE.createAD();
        address.getUses().add(PostalAddressUse.WP);
        TEL telWork = DatatypesFactory.eINSTANCE.createTEL();
        telWork.getUses().add(TelecommunicationAddressUse.WP);

        if (Objects.nonNull(doctorDetails)) {
            assignedAuthor.getIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.4.6", StringUtils.hasText(doctorDetails.getNpi()) ? doctorDetails.getNpi() : ""));
            DoctorAddress docAddr = doctorDetails.getResidential_address();
            if (Objects.nonNull(docAddr)) {
                address.addStreetAddressLine(StringUtils.hasText(docAddr.getLine1()) ? docAddr.getLine1() : "");
                address.addCity(StringUtils.hasText(docAddr.getCity()) ? docAddr.getCity() : "");
                address.addState(StringUtils.hasText(docAddr.getState()) ? docAddr.getState() : "");
                address.addPostalCode(StringUtils.hasText(docAddr.getPostal_code()) ? docAddr.getPostal_code() : "");
                address.addCountry(StringUtils.hasText(docAddr.getCountry()) ? docAddr.getCountry() : "US");
            }

            if (StringUtils.hasText(doctorDetails.getMobile())) {
                telWork.setValue("tel:" + doctorDetails.getMobile());
            } else {
                telWork.setNullFlavor(NullFlavor.UNK);
            }

        }

        assignedAuthor.getAddrs().add(0, address);
        assignedAuthor.getTelecoms().add(0, telWork);

        AuthoringDevice device = CDAFactory.eINSTANCE.createAuthoringDevice();
        SC manufacturerModelName = DatatypesFactory.eINSTANCE.createSC();
        manufacturerModelName.addText(manufacturerModelNameValue);
        device.setManufacturerModelName(manufacturerModelName);
        SC softwareName = DatatypesFactory.eINSTANCE.createSC();
        softwareName.addText(softwareNameValue);
        device.setSoftwareName(softwareName);
        assignedAuthor.setAssignedAuthoringDevice(device);

        author.setAssignedAuthor(assignedAuthor);
        document.getAuthors().add(author);
    }

    private void addCustodian(ClinicalDocument document, PersonalDetailsData patientData) {
        Custodian custodian = CDAFactory.eINSTANCE.createCustodian();
        AssignedCustodian assignedCustodian = CDAFactory.eINSTANCE.createAssignedCustodian();
        CustodianOrganization organization = CDAFactory.eINSTANCE.createCustodianOrganization();

        int doctorId = Optional.ofNullable(patientData).map(PersonalDetailsData::getCreatedBy).orElse(null);

        DoctorDetailsData doctorDetails = doctorOrNull(doctorId);

        II custodianId = DatatypesFactory.eINSTANCE.createII();
        custodianId.setRoot("2.16.840.1.113883.4.336");
        custodianId.setExtension(StringUtils.hasText(doctorDetails.getCms_certificate_number()) ? doctorDetails.getCms_certificate_number() : "CCN");

        organization.getIds().clear();
        organization.getIds().add(custodianId);

        ON organizationName = DatatypesFactory.eINSTANCE.createON();
        organizationName.addText(custodianOrganizationName);
        organization.setName(organizationName);

        AD address = DatatypesFactory.eINSTANCE.createAD();
        address.getUses().add(PostalAddressUse.WP);
        TEL telOrg = null;

        if (Objects.nonNull(doctorDetails)) {
            DoctorAddress docAddr = doctorDetails.getResidential_address();
            if (Objects.nonNull(docAddr)) {
                address.addStreetAddressLine(StringUtils.hasText(docAddr.getLine1()) ? docAddr.getLine1() : "");
                address.addCity(StringUtils.hasText(docAddr.getCity()) ? docAddr.getCity() : "");
                address.addState(StringUtils.hasText(docAddr.getState()) ? docAddr.getState() : "");
                address.addPostalCode(StringUtils.hasText(docAddr.getPostal_code()) ? docAddr.getPostal_code() : "");
                address.addCountry(StringUtils.hasText(docAddr.getCountry()) ? docAddr.getCountry() : "US");
            }

            if (StringUtils.hasText(doctorDetails.getMobile())) {
                telOrg = DatatypesFactory.eINSTANCE.createTEL("tel:" + doctorDetails.getMobile());
                telOrg.getUses().add(TelecommunicationAddressUse.WP);
            } else {
                telOrg = DatatypesFactory.eINSTANCE.createTEL("tel:");
                telOrg.getUses().add(TelecommunicationAddressUse.WP);
            }
        }

        organization.setAddr(address);
        organization.setTelecom(telOrg);

        assignedCustodian.setRepresentedCustodianOrganization(organization);
        custodian.setAssignedCustodian(assignedCustodian);
        document.setCustodian(custodian);
    }

    private void addLegalAuthenticator(ClinicalDocument document, PersonalDetailsData patientData) {
        LegalAuthenticator authenticator = CDAFactory.eINSTANCE.createLegalAuthenticator();
        authenticator.setTime(DatatypesFactory.eINSTANCE.createTS(getCurrentTimestamp()));

        CS signatureCode = DatatypesFactory.eINSTANCE.createCS();
        signatureCode.setCode("S");
        authenticator.setSignatureCode(signatureCode);

        AssignedEntity assignedEntity = CDAFactory.eINSTANCE.createAssignedEntity();
        assignedEntity.getIds().clear();

        Person assignedPerson = CDAFactory.eINSTANCE.createPerson();
        PN name = DatatypesFactory.eINSTANCE.createPN();
        AD address = DatatypesFactory.eINSTANCE.createAD();
        address.getUses().clear();
        TEL tel = null;

        int doctorId = Optional.ofNullable(patientData).map(PersonalDetailsData::getCreatedBy).orElse(null);
        DoctorDetailsData doctorDetails = doctorOrNull(doctorId);

        if (Objects.nonNull(doctorDetails)) {
            II assignedEntityId = DatatypesFactory.eINSTANCE.createII();
            UUID guidAssignedEntity = UUID.randomUUID();
            assignedEntityId.setRoot(guidAssignedEntity.toString());
            assignedEntity.getIds().add(assignedEntityId);

            name.addGiven(StringUtils.hasText(doctorDetails.getFirst_name()) ? doctorDetails.getFirst_name() : "");
            name.addFamily(StringUtils.hasText(doctorDetails.getLast_name()) ? doctorDetails.getLast_name() : "");

            DoctorAddress docAddr = doctorDetails.getResidential_address();
            if (Objects.nonNull(docAddr)) {
                address.addStreetAddressLine(StringUtils.hasText(docAddr.getLine1()) ? docAddr.getLine1() : "");
                address.addCity(StringUtils.hasText(docAddr.getCity()) ? docAddr.getCity() : "");
                address.addState(StringUtils.hasText(docAddr.getState()) ? docAddr.getState() : "");
                address.addPostalCode(StringUtils.hasText(docAddr.getPostal_code()) ? docAddr.getPostal_code() : "20003");   //if (StringUtils.hasText(docAddr.getPostal_code())) address.addPostalCode(docAddr.getPostal_code());
                address.addCountry("US");
            }
            if (StringUtils.hasText(doctorDetails.getMobile())) {
                tel = DatatypesFactory.eINSTANCE.createTEL("tel:" + doctorDetails.getMobile());
                tel.getUses().add(TelecommunicationAddressUse.WP);
            }
        }

        assignedPerson.getNames().add(name);
        assignedEntity.setAssignedPerson(assignedPerson);
        assignedEntity.getAddrs().add(address);
        assignedEntity.getTelecoms().add(tel);

        Organization representedOrganization = CDAFactory.eINSTANCE.createOrganization();
        representedOrganization.getIds().clear();
        representedOrganization.getIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.4.2", StringUtils.hasText(doctorDetails.getTax_id_number()) ? doctorDetails.getTax_id_number() : "TIN"));
        ON orgName = DatatypesFactory.eINSTANCE.createON();
        orgName.addText(legalAuthenticatorOrganizationName);   //EHR Software Name
        representedOrganization.getNames().clear();
        representedOrganization.getNames().add(orgName);
        assignedEntity.getRepresentedOrganizations().clear();
        assignedEntity.getRepresentedOrganizations().add(representedOrganization);

        authenticator.setAssignedEntity(assignedEntity);
        document.setLegalAuthenticator(authenticator);
    }

    private void addDocumentationOf(ClinicalDocument document, PersonalDetailsData patientData) {
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
        Person assignedPerson = CDAFactory.eINSTANCE.createPerson();
        PN name = DatatypesFactory.eINSTANCE.createPN();
        AD address = DatatypesFactory.eINSTANCE.createAD();
        address.getUses().add(PostalAddressUse.HP);
        TEL tel = null;

        int doctorId = Optional.ofNullable(patientData).map(PersonalDetailsData::getCreatedBy).orElse(null);

        DoctorDetailsData doctorDetails = doctorOrNull(doctorId);

        if (Objects.nonNull(doctorDetails)) {

            assignedEntity.getIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.4.6", StringUtils.hasText(doctorDetails.getNpi()) ? doctorDetails.getNpi() : "NPI"));

            assignedEntity.getIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.4.336", StringUtils.hasText(doctorDetails.getCms_certificate_number()) ? doctorDetails.getCms_certificate_number() : "CCN"));

            name.addGiven(StringUtils.hasText(doctorDetails.getFirst_name()) ? doctorDetails.getFirst_name() : "");
            name.addFamily(StringUtils.hasText(doctorDetails.getLast_name()) ? doctorDetails.getLast_name() : "");

            DoctorAddress docAddr = doctorDetails.getResidential_address();
            if (Objects.nonNull(docAddr)) {
                address.addStreetAddressLine(StringUtils.hasText(docAddr.getLine1()) ? docAddr.getLine1() : "");
                address.addCity(StringUtils.hasText(docAddr.getCity()) ? docAddr.getCity() : "");
                address.addState(StringUtils.hasText(docAddr.getState()) ? docAddr.getState() : "");
                address.addPostalCode(StringUtils.hasText(docAddr.getPostal_code()) ? docAddr.getPostal_code() : "");
                address.addCountry(StringUtils.hasText(docAddr.getCountry()) ? docAddr.getCountry() : "US");
            }
        }

        CE providerCode = DatatypesFactory.eINSTANCE.createCE();
        providerCode.setCode(Objects.nonNull(doctorDetails) && StringUtils.hasText(doctorDetails.getTaxonomy_code()) ? doctorDetails.getTaxonomy_code() : "Taxonomy_Code");
        providerCode.setCodeSystem("2.16.840.1.113883.6.101");
        providerCode.setCodeSystemName("Healthcare Provider Taxonomy (HIPAA)");
        assignedEntity.setCode(providerCode);

        assignedPerson.getNames().add(name);
        assignedEntity.setAssignedPerson(assignedPerson);
        assignedEntity.getAddrs().add(address);
        performer.setAssignedEntity(assignedEntity);
        serviceEvent.getPerformers().add(performer);

        Organization representedOrg = CDAFactory.eINSTANCE.createOrganization();
        if (Objects.nonNull(doctorDetails) && StringUtils.hasText(doctorDetails.getNpi())) {
            representedOrg.getIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.4.2", doctorDetails.getNpi()));
        } else {
            representedOrg.getIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.4.2", ""));
        }

        AD orgAddr = DatatypesFactory.eINSTANCE.createAD();
        orgAddr.getUses().add(PostalAddressUse.WP);
        Optional<ClinicAddress> clinicAddressOpt = Optional.ofNullable(doctorDetails).map(DoctorDetailsData::getClinics)
                .filter(clinics -> !clinics.isEmpty())
                .flatMap(clinics -> clinics.stream().findFirst())
                .map(Clinic::getAddress);
        if (clinicAddressOpt.isPresent()) {
            ClinicAddress clinicAddress = clinicAddressOpt.get();
            orgAddr.addStreetAddressLine(StringUtils.hasText(clinicAddress.getLine1()) ? clinicAddress.getLine1() : "");
            orgAddr.addCity(StringUtils.hasText(clinicAddress.getCity()) ? clinicAddress.getCity() : "");
            orgAddr.addState(StringUtils.hasText(clinicAddress.getState()) ? clinicAddress.getState() : "");
            orgAddr.addPostalCode(StringUtils.hasText(clinicAddress.getPostal_code()) ? clinicAddress.getPostal_code() : "");
            orgAddr.addCountry(StringUtils.hasText(clinicAddress.getCountry()) ? clinicAddress.getCountry() : "US");
        }
        representedOrg.getAddrs().add(orgAddr);
        assignedEntity.getRepresentedOrganizations().clear();
        assignedEntity.getRepresentedOrganizations().add(representedOrg);

        documentationOf.setServiceEvent(serviceEvent);
        document.getDocumentationOfs().add(documentationOf);
    }

    private void addMeasureSection(StructuredBody structuredBody) {
        Section section = CDAFactory.eINSTANCE.createSection();
        section.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.24.2.2"));
        section.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.24.2.3"));
        section.setCode(DatatypesFactory.eINSTANCE.createCE("55186-1", "2.16.840.1.113883.6.1", null, null));
        section.setTitle(DatatypesFactory.eINSTANCE.createST("Measure Section"));

        String CMS139Title = "Percentage of patients 65 years of age and older who were screened for future fall risk during the measurement period";
        String CMS139HQMF = "8A6D0454-8DF0-2D9F-018E-1434289012A6";
        UUID CMS139_SET_ID = UUID.randomUUID();

        StrucDocText text = CDAFactory.eINSTANCE.createStrucDocText();
        text.addText(CMS139Title);
        text.addText("(HQMF ID: " + CMS139HQMF + ")");
        section.setText(text);

        Entry entry = CDAFactory.eINSTANCE.createEntry();
        Organizer organizer = CDAFactory.eINSTANCE.createOrganizer();
        organizer.setClassCode(x_ActClassDocumentEntryOrganizer.CLUSTER);
        organizer.setMoodCode(ActMood.EVN);

        organizer.setStatusCode(DatatypesFactory.eINSTANCE.createCS("completed"));
        organizer.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.24.3.98"));
        organizer.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.24.3.97"));
        UUID measureSectionUUID = UUID.randomUUID();
        organizer.getIds().add(DatatypesFactory.eINSTANCE.createII(measureSectionUUID.toString(), "1.3.6.1.4.1.115"));    //Review this PatientUUID
        Reference reference = CDAFactory.eINSTANCE.createReference();
        reference.setTypeCode(x_ActRelationshipExternalReference.REFR);
        ExternalDocument externalDocument = CDAFactory.eINSTANCE.createExternalDocument();
        externalDocument.setClassCode(ActClassDocument.DOC);
        externalDocument.setMoodCode(ActMood.EVN);
        II extId = DatatypesFactory.eINSTANCE.createII();
        extId.setRoot("2.16.840.1.113883.4.738");
        extId.setExtension(CMS139HQMF);
        externalDocument.getIds().add(extId);
        ED docText = DatatypesFactory.eINSTANCE.createED();
        docText.addText(CMS139Title);
        externalDocument.setText(docText);
        II setId = DatatypesFactory.eINSTANCE.createII();
        setId.setRoot(CMS139_SET_ID.toString().toUpperCase());
        externalDocument.setSetId(setId);
        reference.setExternalDocument(externalDocument);
        organizer.getReferences().add(reference);
        entry.setOrganizer(organizer);
        section.getEntries().add(entry);

        Component3 component3 = CDAFactory.eINSTANCE.createComponent3();
        component3.setSection(section);
        structuredBody.getComponents().add(component3);
    }

    private void addReportingParametersSection(StructuredBody structuredBody) {
        Section section = CDAFactory.eINSTANCE.createSection();
        section.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.17.2.1"));
        section.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.17.2.1.1", "2016-03-01"));
        section.setCode(DatatypesFactory.eINSTANCE.createCE("55187-9", "2.16.840.1.113883.6.1", null, null));
        section.setTitle(DatatypesFactory.eINSTANCE.createST("Reporting Parameters"));

        StrucDocText text = CDAFactory.eINSTANCE.createStrucDocText();
        text.addText("Reporting Period: " + "20230101" + " to " + "20231231");
        section.setText(text);

        Entry entry = CDAFactory.eINSTANCE.createEntry();
        entry.setTypeCode(x_ActRelationshipEntry.DRIV);
        Act act = CDAFactory.eINSTANCE.createAct();
        act.setClassCode(x_ActClassDocumentEntryAct.ACT);
        act.setMoodCode(x_DocumentActMood.EVN);
        act.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.17.3.8"));
        act.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.17.3.8.1", "2016-03-01"));
        UUID rpsUUID = UUID.randomUUID();
        act.getIds().add(DatatypesFactory.eINSTANCE.createII(rpsUUID.toString(), "1.3.6.1.4.1.115"));
        act.setCode(DatatypesFactory.eINSTANCE.createCE("252116004", "2.16.840.1.113883.6.96", "Observation Parameters", null));

        IVL_TS effectiveTime = DatatypesFactory.eINSTANCE.createIVL_TS();
        IVXB_TS low = DatatypesFactory.eINSTANCE.createIVXB_TS();
        low.setValue("20230101000000");
        IVXB_TS high = DatatypesFactory.eINSTANCE.createIVXB_TS();
        high.setValue("20231231235959");
        effectiveTime.setLow(low);
        effectiveTime.setHigh(high);
        act.setEffectiveTime(effectiveTime);

        entry.setAct(act);
        section.getEntries().add(entry);

        Component3 component3 = CDAFactory.eINSTANCE.createComponent3();
        component3.setSection(section);
        structuredBody.getComponents().add(component3);
    }

    private void addPatientDataSection(StructuredBody body, PersonalDetailsData patientData, List<InsuranceDetails> insuranceDetails, List<Appointment> appointments) {
        Component3 component = CDAFactory.eINSTANCE.createComponent3();
        Section section = CDAFactory.eINSTANCE.createSection();
        section.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.17.2.4"));
        section.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.24.2.1", "2021-08-01"));
        section.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.24.2.1.1", "2022-02-01"));
        section.setCode(DatatypesFactory.eINSTANCE.createCE("55188-7", "2.16.840.1.113883.6.1", null, null));
        section.setTitle(DatatypesFactory.eINSTANCE.createST("Patient Data"));
        section.setText(CDAFactory.eINSTANCE.createStrucDocText());

        if (!CollectionUtils.isEmpty(appointments)) {
            for (Appointment appointment : appointments) {
                if (Objects.nonNull(appointment)) {
                    Entry encounterEntry = CDAFactory.eINSTANCE.createEntry();
                    org.openhealthtools.mdht.uml.cda.Encounter encounter = CDAFactory.eINSTANCE.createEncounter();
                    encounter.setClassCode(org.openhealthtools.mdht.uml.hl7.vocab.ActClass.ENC);
                    encounter.setMoodCode(org.openhealthtools.mdht.uml.hl7.vocab.x_DocumentEncounterMood.EVN);
                    encounter.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.22.4.49", "2015-08-01"));
                    encounter.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.24.3.23", "2021-08-01"));

                    UUID encounterUUID = UUID.randomUUID();
                    encounter.getIds().add(DatatypesFactory.eINSTANCE.createII("1.3.6.1.4.1.115", encounterUUID.toString()));

                    CE code = DatatypesFactory.eINSTANCE.createCE();

                    // Determine CPT code based on category name
                    String cptCode = "99213"; // Default
                    if (Objects.nonNull(appointment.getCategory()) && !appointment.getCategory().isEmpty()) {
                        for (AppointmentCategory category : appointment.getCategory()) {
                            if (Objects.nonNull(category) && StringUtils.hasText(category.getName())) {
                                if ("Initial Evaluation".equalsIgnoreCase(category.getName().trim())) {
                                    cptCode = "99203";
                                    break;
                                } else if ("Follow-up".equalsIgnoreCase(category.getName().trim()) ||
                                        "follow-up".equalsIgnoreCase(category.getName().trim())) {
                                    cptCode = "99213";
                                    break;
                                }
                            }
                        }
                    }

                    code.setCode(cptCode);
                    code.setCodeSystem("2.16.840.1.113883.6.12");
                    code.setCodeSystemName("CPT");
                    encounter.setCode(code);
                    encounter.setText(DatatypesFactory.eINSTANCE.createST("Office Visit"));
                    encounter.setStatusCode(DatatypesFactory.eINSTANCE.createCS("completed"));

                    IVL_TS effectiveTime = DatatypesFactory.eINSTANCE.createIVL_TS();

                    IVXB_TS low = DatatypesFactory.eINSTANCE.createIVXB_TS();
                    if (StringUtils.hasText(appointment.getDate_time())) {
                        try {
                            long epochTime = Long.parseLong(appointment.getDate_time());
                            String formattedDateTime = convertEpochToStandardTime(epochTime);
                            low.setValue(formattedDateTime);
                        } catch (NumberFormatException e) {
                            low.setNullFlavor(NullFlavor.UNK);
                        }
                    } else {
                        low.setNullFlavor(NullFlavor.UNK);
                    }
                    effectiveTime.setLow(low);

                    IVXB_TS high = DatatypesFactory.eINSTANCE.createIVXB_TS();
                    if (StringUtils.hasText(appointment.getEnd_date_time())) {
                        try {
                            long epochTime = Long.parseLong(appointment.getEnd_date_time());
                            String formattedEndDateTime = convertEpochToStandardTime(epochTime);
                            high.setValue(formattedEndDateTime);
                        } catch (NumberFormatException e) {
                            high.setNullFlavor(NullFlavor.UNK);
                        }
                    } else {
                        high.setNullFlavor(NullFlavor.UNK);
                    }
                    effectiveTime.setHigh(high);

                    encounter.setEffectiveTime(effectiveTime);
                    encounterEntry.setEncounter(encounter);
                    section.getEntries().add(encounterEntry);
                }
            }
        }

        // Fetch SOAP details
        List<FormData> soapDetails = new ArrayList<>();
        if (Objects.nonNull(patientData) && Objects.nonNull(patientData.getResponse()) && !CollectionUtils.isEmpty(patientData.getResponse().getPatientInformation())) {
            Map<String, PatientInformation> patientInfoMap = patientData.getResponse().getPatientInformation();
            PatientInformation patientInfo = patientInfoMap.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
            if (Objects.nonNull(patientInfo)) {
                try {
                    String fhirId = patientData.getOrganisationId() + "-" + patientData.getPatientId();
                    List<FormData> soapResponse = fetchSoapDetails(fhirId);
                    if (Objects.nonNull(soapResponse)) {
                        soapDetails = soapResponse;
                    }
                } catch (Exception e) {
                    log.error("Error fetching SOAP details: {}", e.getMessage());
                }
            }
        }

        //----------------------------------  Dynamic Assessment and Intervention Entries   ------------------------------------------

        for (FormData formData : soapDetails) {
            if (Objects.nonNull(formData) && Objects.nonNull(formData.getResponse())) {
                FormResponse response = formData.getResponse();

                // Process Assessment entries
                if (Objects.nonNull(response.getAssessment())) {
                    for (Map.Entry<String, CodeSection> assessmentEntry : response.getAssessment().entrySet()) {
                        if (Objects.nonNull(assessmentEntry.getValue())) {
                            CodeSection codeSection = assessmentEntry.getValue();

                            // LOINC codes for Assessment
                            List<LoincCode> loincCodes = parseLoincCodes(codeSection.getLoincCodes());
                            for (LoincCode loincCode : loincCodes) {
                                if (Objects.nonNull(loincCode) && StringUtils.hasText(loincCode.getCode()) &&
                                        ("LC-73830-2".equals(loincCode.getCode()))) {
                                    Entry assessmentObservationEntry = createAssessmentObservationEntry(loincCode);
                                    if (Objects.nonNull(assessmentObservationEntry)) {
                                        section.getEntries().add(assessmentObservationEntry);
                                    }
                                } else if (Objects.nonNull(loincCode) && StringUtils.hasText(loincCode.getCode()) &&
                                        "LC-45755-6".equals(loincCode.getCode())) {
                                    Entry assessmentObservationEntry = createAssessmentObservationEntry2(loincCode);
                                    if (Objects.nonNull(assessmentObservationEntry)) {
                                        section.getEntries().add(assessmentObservationEntry);
                                    }
                                }
                            }

//                            List<SnomedCode> snomedCodes = parseSnomedCodes(codeSection.getSnomedCodes());
//                            for (SnomedCode snomedCode : snomedCodes) {
//                                if (Objects.nonNull(snomedCode) && StringUtils.hasText(snomedCode.getCode())) {
//                                    if ("SCT-32485007".equals(snomedCode.getCode())) {
//                                        Entry inpatientEncounterEntry = createInpatientEncounterEntry(snomedCode);
//                                        if (Objects.nonNull(inpatientEncounterEntry)) {
//                                            section.getEntries().add(inpatientEncounterEntry);
//                                        }
//                                    } else if ("SCT-183919006".equals(snomedCode.getCode())) {
//                                        Entry hospiceEncounterEntry = createHospiceEncounterEntry(snomedCode);
//                                        if (Objects.nonNull(hospiceEncounterEntry)) {
//                                            section.getEntries().add(hospiceEncounterEntry);
//                                        }
//                                    }
//                                }
//                            }

                        }
                    }
                }

                // Process Intervention entries
                if (Objects.nonNull(response.getIntervention())) {
                    for (Map.Entry<String, CodeSection> interventionEntry : response.getIntervention().entrySet()) {
                        if (Objects.nonNull(interventionEntry.getValue())) {
                            CodeSection codeSection = interventionEntry.getValue();

                            List<SnomedCode> snomedCodes = parseSnomedCodes(codeSection.getSnomedCodes());
                            for (SnomedCode snomedCode : snomedCodes) {
                                if (Objects.nonNull(snomedCode) && StringUtils.hasText(snomedCode.getCode()) &&
                                        "SCT-385763009".equals(snomedCode.getCode()) && "Active".equals(snomedCode.getStatus())) {

                                    // Create Intervention Order entry
                                    Entry interventionOrderEntry = createInterventionOrderEntry(snomedCode);
                                    if (Objects.nonNull(interventionOrderEntry)) {
                                        section.getEntries().add(interventionOrderEntry);
                                    }
                                }

                                if (Objects.nonNull(snomedCode) && StringUtils.hasText(snomedCode.getCode()) &&
                                        "SCT-385763009".equals(snomedCode.getCode()) && "Completed".equals(snomedCode.getStatus())) {

                                    // Create Intervention Performed entry
                                    Entry interventionPerformedEntry = createInterventionPerformedEntry(snomedCode);
                                    if (Objects.nonNull(interventionPerformedEntry)) {
                                        section.getEntries().add(interventionPerformedEntry);
                                    }
                                }

                                if (Objects.nonNull(snomedCode) && StringUtils.hasText(snomedCode.getCode())) {
                                    if ("SCT-32485007".equals(snomedCode.getCode())) {
                                        Entry inpatientEncounterEntry = createInpatientEncounterEntry(snomedCode);
                                        if (Objects.nonNull(inpatientEncounterEntry)) {
                                            section.getEntries().add(inpatientEncounterEntry);
                                        }
                                    } else if ("SCT-183919006".equals(snomedCode.getCode())) {
                                        Entry hospiceEncounterEntry = createHospiceEncounterEntry(snomedCode);
                                        if (Objects.nonNull(hospiceEncounterEntry)) {
                                            section.getEntries().add(hospiceEncounterEntry);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Patient Characteristic Payer
        Entry payerEntry = CDAFactory.eINSTANCE.createEntry();
        Observation payerObservation = CDAFactory.eINSTANCE.createObservation();
        payerObservation.setClassCode(ActClassObservation.OBS);
        payerObservation.setMoodCode(x_ActMoodDocumentObservation.EVN);
        payerObservation.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.24.3.55"));
        UUID payerUUID = UUID.randomUUID();
        payerObservation.getIds().add(DatatypesFactory.eINSTANCE.createII(payerUUID.toString()));
        CE payerCode = DatatypesFactory.eINSTANCE.createCE();
        payerCode.setCode("48768-6");
        payerCode.setCodeSystem("2.16.840.1.113883.6.1");
        payerCode.setCodeSystemName("LOINC");
        payerCode.setDisplayName("Payment source");
        payerObservation.setCode(payerCode);
        payerObservation.setStatusCode(DatatypesFactory.eINSTANCE.createCS("completed"));
        IVL_TS payerEffectiveTime = DatatypesFactory.eINSTANCE.createIVL_TS();

        if (!CollectionUtils.isEmpty(insuranceDetails)) {
            for (InsuranceDetails details : insuranceDetails) {
                if (Objects.nonNull(details)) {

                    SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd"); // this is the input date format
                    SimpleDateFormat outputFormat = new SimpleDateFormat("yyyyMMddHHmmss");

                    if (StringUtils.hasText(details.getPlan_start_date())) {
                        try {
                            java.util.Date startDate = inputFormat.parse(details.getPlan_start_date());
                            IVXB_TS insuaranceStartTime = DatatypesFactory.eINSTANCE.createIVXB_TS();
                            insuaranceStartTime.setValue(outputFormat.format(startDate));
                            payerEffectiveTime.setLow(insuaranceStartTime);
                        } catch (java.text.ParseException e) {
                            IVXB_TS insuaranceStartTime = DatatypesFactory.eINSTANCE.createIVXB_TS();
                            insuaranceStartTime.setNullFlavor(NullFlavor.UNK);
                            payerEffectiveTime.setLow(insuaranceStartTime);
                        }
                    } else {
                        IVXB_TS insuaranceStartTime = DatatypesFactory.eINSTANCE.createIVXB_TS();
                        insuaranceStartTime.setNullFlavor(NullFlavor.UNK);
                        payerEffectiveTime.setLow(insuaranceStartTime);
                    }

                    IVXB_TS insuaranceEndDate = DatatypesFactory.eINSTANCE.createIVXB_TS();
                    if (StringUtils.hasText(details.getPlan_end_date())) {
                        try {
                            java.util.Date endDate = inputFormat.parse(details.getPlan_end_date());
                            insuaranceEndDate.setValue(outputFormat.format(endDate));
                            payerEffectiveTime.setHigh(insuaranceEndDate);
                        } catch (java.text.ParseException e) {
                            insuaranceEndDate.setNullFlavor(NullFlavor.UNK);
                        }
                    } else {
                        insuaranceEndDate.setNullFlavor(NullFlavor.UNK);
                    }
                    payerObservation.setEffectiveTime(payerEffectiveTime);

                    CD payerValue = DatatypesFactory.eINSTANCE.createCD();
                    if (StringUtils.hasText(details.getPayer_type())) {
                        String sourceType = details.getPayer_type().toLowerCase();
                        switch (sourceType) {
                            case "medicare":
                                payerValue.setCode("1");
                                break;
                            case "medicaid":
                                payerValue.setCode("2");
                                break;
                            case "other":
                                payerValue.setCode("9");
                                break;
                            default:
                                payerValue.setNullFlavor(NullFlavor.UNK);
                                break;
                        }
                    } else {
                        payerValue.setNullFlavor(NullFlavor.UNK);
                    }
                    payerValue.setCodeSystem("2.16.840.1.113883.3.221.5");
                    payerValue.setCodeSystemName("Source of Payment Typology");
                    payerObservation.getValues().add(payerValue);

                    payerEntry.setObservation(payerObservation);
                    section.getEntries().add(payerEntry);
                }
            }
        }
        component.setSection(section);
        body.getComponents().add(component);
    }


    // method to create Assessment Observation Entry for LOINC codes
    private Entry createAssessmentObservationEntry(LoincCode loincCode) {
        try {
            Entry entry = CDAFactory.eINSTANCE.createEntry();
            Observation observation = CDAFactory.eINSTANCE.createObservation();
            observation.setClassCode(ActClassObservation.OBS);
            observation.setMoodCode(x_ActMoodDocumentObservation.EVN);

            II templateId = DatatypesFactory.eINSTANCE.createII();
            templateId.setRoot("2.16.840.1.113883.10.20.24.3.144");
            templateId.setExtension("2021-08-01");
            observation.getTemplateIds().add(templateId);

            II obsId = DatatypesFactory.eINSTANCE.createII();
            obsId.setRoot("1.3.6.1.4.1.115");
            UUID obsIdUUID = UUID.randomUUID();
            obsId.setExtension(obsIdUUID.toString());
            observation.getIds().add(obsId);

            CE apCode = DatatypesFactory.eINSTANCE.createCE();
            apCode.setCode("73830-2");
            apCode.setCodeSystem("2.16.840.1.113883.6.1");
            apCode.setCodeSystemName("LOINC");
            observation.setCode(apCode);

            ED text = DatatypesFactory.eINSTANCE.createED();
            text.addText("Falls Screening");
            observation.setText(text);

            CS statusCode = DatatypesFactory.eINSTANCE.createCS();
            statusCode.setCode("completed");
            observation.setStatusCode(statusCode);

            IVL_TS apEffectiveTime = DatatypesFactory.eINSTANCE.createIVL_TS();
            if (StringUtils.hasText(loincCode.getStartDate())) {
                String formattedDate = convertToReqdFormatDate(loincCode.getStartDate());
                if (StringUtils.hasText(formattedDate)) {
                    apEffectiveTime.setValue(formattedDate);
                }
            }
            observation.setEffectiveTime(apEffectiveTime);

            Author author = CDAFactory.eINSTANCE.createAuthor();
            II authorTemplateId = DatatypesFactory.eINSTANCE.createII();
            authorTemplateId.setRoot("2.16.840.1.113883.10.20.24.3.155");
            authorTemplateId.setExtension("2019-12-01");
            author.getTemplateIds().add(authorTemplateId);

            TS authorTime = DatatypesFactory.eINSTANCE.createTS();
            if (StringUtils.hasText(loincCode.getStartDate())) {
                String formattedDate = convertToReqdFormatDate(loincCode.getStartDate());
                if (StringUtils.hasText(formattedDate)) {
                    authorTime.setValue(formattedDate);
                }
            }
            author.setTime(authorTime);

            AssignedAuthor assignedAuthor = CDAFactory.eINSTANCE.createAssignedAuthor();
            II assignedAuthorId = DatatypesFactory.eINSTANCE.createII();
            assignedAuthorId.setNullFlavor(NullFlavor.NA);
            assignedAuthor.getIds().add(assignedAuthorId);

            author.setAssignedAuthor(assignedAuthor);
            observation.getAuthors().add(author);

            entry.setObservation(observation);
            return entry;
        } catch (Exception e) {
            log.error("Error creating assessment observation entry: {}", e.getMessage());
            return null;
        }
    }

    // method to create Assessment SNOMED Entry
    private Entry createAssessmentObservationEntry2(LoincCode loincCode) {
        try {
            Entry entry = CDAFactory.eINSTANCE.createEntry();
            Observation observation = CDAFactory.eINSTANCE.createObservation();
            observation.setClassCode(ActClassObservation.OBS);
            observation.setMoodCode(x_ActMoodDocumentObservation.EVN);

            II templateId = DatatypesFactory.eINSTANCE.createII();
            templateId.setRoot("2.16.840.1.113883.10.20.24.3.144");
            templateId.setExtension("2021-08-01");
            observation.getTemplateIds().add(templateId);

            II obsId = DatatypesFactory.eINSTANCE.createII();
            obsId.setRoot("1.3.6.1.4.1.115");
            UUID obsIdUUID = UUID.randomUUID();
            obsId.setExtension(obsIdUUID.toString());
            observation.getIds().add(obsId);

            CE apCode = DatatypesFactory.eINSTANCE.createCE();
            apCode.setCode("45755-6");
            apCode.setCodeSystem("2.16.840.1.113883.6.1");
            apCode.setCodeSystemName("LOINC");
            observation.setCode(apCode);

            ED text = DatatypesFactory.eINSTANCE.createED();
            text.addText("Hospice care [Minimum Data Set]");
            observation.setText(text);

            CS statusCode = DatatypesFactory.eINSTANCE.createCS();
            statusCode.setCode("completed");
            observation.setStatusCode(statusCode);

            IVL_TS apEffectiveTime = DatatypesFactory.eINSTANCE.createIVL_TS();
            if (StringUtils.hasText(loincCode.getStartDate())) {
                String formattedStartDate = convertToReqdFormatDate(loincCode.getStartDate());
                if (StringUtils.hasText(formattedStartDate)) {
                    IVXB_TS low = DatatypesFactory.eINSTANCE.createIVXB_TS();
                    low.setValue(formattedStartDate);
                    apEffectiveTime.setLow(low);
                }
            }
            if (StringUtils.hasText(loincCode.getEndDate())) {
                String formattedEndDate = convertToReqdFormatDate(loincCode.getEndDate());
                if (StringUtils.hasText(formattedEndDate)) {
                    IVXB_TS high = DatatypesFactory.eINSTANCE.createIVXB_TS();
                    high.setValue(formattedEndDate);
                    apEffectiveTime.setHigh(high);
                }
            }
            observation.setEffectiveTime(apEffectiveTime);

            // Add SNOMED value
            CD value = DatatypesFactory.eINSTANCE.createCD();
            value.setCode("373066001");
            value.setCodeSystem("2.16.840.1.113883.6.96");
            value.setCodeSystemName("SNOMEDCT");
            observation.getValues().add(value);

            // Add author
            Author author = CDAFactory.eINSTANCE.createAuthor();
            II authorTemplateId = DatatypesFactory.eINSTANCE.createII();
            authorTemplateId.setRoot("2.16.840.1.113883.10.20.24.3.155");
            authorTemplateId.setExtension("2019-12-01");
            author.getTemplateIds().add(authorTemplateId);

            TS authorTime = DatatypesFactory.eINSTANCE.createTS();
            String endDate = convertToReqdFormatDate(loincCode.getEndDate());
            authorTime.setValue(endDate);
            author.setTime(authorTime);

            AssignedAuthor assignedAuthor = CDAFactory.eINSTANCE.createAssignedAuthor();
            II assignedAuthorId = DatatypesFactory.eINSTANCE.createII();
            assignedAuthorId.setNullFlavor(NullFlavor.NA);
            assignedAuthor.getIds().add(assignedAuthorId);

            author.setAssignedAuthor(assignedAuthor);
            observation.getAuthors().add(author);

            entry.setObservation(observation);
            return entry;
        } catch (Exception e) {
            log.error("Error creating assessment SNOMED entry: {}", e.getMessage());
            return null;
        }
    }

    // method to create Intervention Order Entry
    private Entry createInterventionOrderEntry(SnomedCode snomedCode) {
        try {
            Entry entry = CDAFactory.eINSTANCE.createEntry();
            Act act = CDAFactory.eINSTANCE.createAct();
            act.setClassCode(x_ActClassDocumentEntryAct.ACT);
            act.setMoodCode(x_DocumentActMood.RQO);

            II plannedActTemplateId = DatatypesFactory.eINSTANCE.createII();
            plannedActTemplateId.setRoot("2.16.840.1.113883.10.20.22.4.39");
            plannedActTemplateId.setExtension("2014-06-09");
            act.getTemplateIds().add(plannedActTemplateId);

            II interventionOrderTemplateId = DatatypesFactory.eINSTANCE.createII();
            interventionOrderTemplateId.setRoot("2.16.840.1.113883.10.20.24.3.31");
            interventionOrderTemplateId.setExtension("2021-08-01");
            act.getTemplateIds().add(interventionOrderTemplateId);

            II actId = DatatypesFactory.eINSTANCE.createII();
            actId.setRoot("1.3.6.1.4.1.115");
            UUID actIdUUID = UUID.randomUUID();
            actId.setExtension(actIdUUID.toString());
            act.getIds().add(actId);

            CE actCode = DatatypesFactory.eINSTANCE.createCE();
            actCode.setCode("385763009");
            actCode.setCodeSystem("2.16.840.1.113883.6.96");
            actCode.setCodeSystemName("SNOMEDCT");
            act.setCode(actCode);

            ED actText = DatatypesFactory.eINSTANCE.createED();
            actText.addText("Hospice Care Ambulatory");
            act.setText(actText);

            CS actStatus = DatatypesFactory.eINSTANCE.createCS();
            if (StringUtils.hasText(snomedCode.getStatus())) {
                actStatus.setCode(snomedCode.getStatus().toLowerCase());
            } else {
                actStatus.setCode("active");
            }
            act.setStatusCode(actStatus);

            // Add author
            Author author = CDAFactory.eINSTANCE.createAuthor();
            II authorTemplateId = DatatypesFactory.eINSTANCE.createII();
            authorTemplateId.setRoot("2.16.840.1.113883.10.20.24.3.155");
            authorTemplateId.setExtension("2019-12-01");
            author.getTemplateIds().add(authorTemplateId);

            TS authorTime = DatatypesFactory.eINSTANCE.createTS();
            String startTime = convertToReqdFormatDate(snomedCode.getStartDate());
            authorTime.setValue(startTime);
            author.setTime(authorTime);

            AssignedAuthor assignedAuthor = CDAFactory.eINSTANCE.createAssignedAuthor();
            II assignedAuthorId = DatatypesFactory.eINSTANCE.createII();
            assignedAuthorId.setNullFlavor(NullFlavor.NA);
            assignedAuthor.getIds().add(assignedAuthorId);

            author.setAssignedAuthor(assignedAuthor);
            act.getAuthors().add(author);

            entry.setAct(act);
            return entry;
        } catch (Exception e) {
            log.error("Error creating intervention order entry: {}", e.getMessage());
            return null;
        }
    }

    // method to create Intervention Performed Entry
    private Entry createInterventionPerformedEntry(SnomedCode snomedCode) {
        try {
            Entry entry = CDAFactory.eINSTANCE.createEntry();
            Act act = CDAFactory.eINSTANCE.createAct();
            act.setClassCode(x_ActClassDocumentEntryAct.ACT);
            act.setMoodCode(x_DocumentActMood.EVN);

            II procedureActivityTemplateId = DatatypesFactory.eINSTANCE.createII();
            procedureActivityTemplateId.setRoot("2.16.840.1.113883.10.20.22.4.12");
            procedureActivityTemplateId.setExtension("2014-06-09");
            act.getTemplateIds().add(procedureActivityTemplateId);

            II interventionCompletedTemplateId = DatatypesFactory.eINSTANCE.createII();
            interventionCompletedTemplateId.setRoot("2.16.840.1.113883.10.20.24.3.32");
            interventionCompletedTemplateId.setExtension("2021-08-01");
            act.getTemplateIds().add(interventionCompletedTemplateId);

            II actId = DatatypesFactory.eINSTANCE.createII();
            actId.setRoot("1.3.6.1.4.1.115");
            UUID actIdUUID = UUID.randomUUID();
            actId.setExtension(actIdUUID.toString());
            act.getIds().add(actId);

            CE actCode = DatatypesFactory.eINSTANCE.createCE();
            actCode.setCode("385763009");
            actCode.setCodeSystem("2.16.840.1.113883.6.96");
            actCode.setCodeSystemName("SNOMEDCT");
            act.setCode(actCode);

            ED actText = DatatypesFactory.eINSTANCE.createED();
            actText.addText("Hospice Care Ambulatory");
            act.setText(actText);

            CS actStatus = DatatypesFactory.eINSTANCE.createCS();
            if (StringUtils.hasText(snomedCode.getStatus())) {
                actStatus.setCode(snomedCode.getStatus().toLowerCase());
            } else {
                actStatus.setCode("completed");
            }
            act.setStatusCode(actStatus);

            IVL_TS effectiveTime = DatatypesFactory.eINSTANCE.createIVL_TS();
            if (StringUtils.hasText(snomedCode.getStartDate())) {
                String formattedStartDate = convertToReqdFormatDate(snomedCode.getStartDate());
                if (StringUtils.hasText(formattedStartDate)) {
                    IVXB_TS low = DatatypesFactory.eINSTANCE.createIVXB_TS();
                    low.setValue(formattedStartDate);
                    effectiveTime.setLow(low);
                }
            }
            if (StringUtils.hasText(snomedCode.getEndDate())) {
                String formattedEndDate = convertToReqdFormatDate(snomedCode.getEndDate());
                if (StringUtils.hasText(formattedEndDate)) {
                    IVXB_TS high = DatatypesFactory.eINSTANCE.createIVXB_TS();
                    high.setValue(formattedEndDate);
                    effectiveTime.setHigh(high);
                }
            }
            act.setEffectiveTime(effectiveTime);

            entry.setAct(act);
            return entry;
        } catch (Exception e) {
            log.error("Error creating intervention performed entry: {}", e.getMessage());
            return null;
        }
    }

    private Entry createInpatientEncounterEntry(SnomedCode snomedCode) {
        try {
            Entry entry = CDAFactory.eINSTANCE.createEntry();
            Encounter encounter = CDAFactory.eINSTANCE.createEncounter();
            encounter.setClassCode(org.openhealthtools.mdht.uml.hl7.vocab.ActClass.ENC);
            encounter.setMoodCode(org.openhealthtools.mdht.uml.hl7.vocab.x_DocumentEncounterMood.EVN);

            encounter.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.22.4.49", "2015-08-01"));
            encounter.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.24.3.23", "2021-08-01"));

            II encId = DatatypesFactory.eINSTANCE.createII();
            encId.setRoot("1.3.6.1.4.1.115");
            encId.setExtension(UUID.randomUUID().toString());
            encounter.getIds().add(encId);

            CE encCode = DatatypesFactory.eINSTANCE.createCE("32485007", "2.16.840.1.113883.6.96", "SNOMEDCT", null);
            encounter.setCode(encCode);

            ED text = DatatypesFactory.eINSTANCE.createED();
            text.addText("Encounter Inpatient");
            encounter.setText(text);

            CS statusCode = DatatypesFactory.eINSTANCE.createCS("completed");
            encounter.setStatusCode(statusCode);

            IVL_TS effectiveTime = DatatypesFactory.eINSTANCE.createIVL_TS();

            if (StringUtils.hasText(snomedCode.getStartDate())) {
                String formattedStartDate = convertToReqdFormatDate(snomedCode.getStartDate());
                if (StringUtils.hasText(formattedStartDate)) {
                    IVXB_TS low = DatatypesFactory.eINSTANCE.createIVXB_TS();
                    low.setValue(formattedStartDate);
                    effectiveTime.setLow(low);
                }
            }

            if (StringUtils.hasText(snomedCode.getEndDate())) {
                String formattedEndDate = convertToReqdFormatDate(snomedCode.getEndDate());
                if (StringUtils.hasText(formattedEndDate)) {
                    IVXB_TS high = DatatypesFactory.eINSTANCE.createIVXB_TS();
                    high.setValue(formattedEndDate);
                    effectiveTime.setHigh(high);
                }
            }

            encounter.setEffectiveTime(effectiveTime);

            entry.setEncounter(encounter);
            return entry;
        } catch (Exception e) {
            log.error("Error creating inpatient encounter entry for 32485007: {}", e.getMessage());
            return null;
        }
    }

    private Entry createHospiceEncounterEntry(SnomedCode snomedCode) {
        try {
            Entry entry = CDAFactory.eINSTANCE.createEntry();
            Encounter encounter = CDAFactory.eINSTANCE.createEncounter();
            encounter.setClassCode(org.openhealthtools.mdht.uml.hl7.vocab.ActClass.ENC);
            encounter.setMoodCode(org.openhealthtools.mdht.uml.hl7.vocab.x_DocumentEncounterMood.EVN);

            encounter.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.22.4.49", "2015-08-01"));
            encounter.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.24.3.23", "2021-08-01"));

            II encId = DatatypesFactory.eINSTANCE.createII();
            encId.setRoot("1.3.6.1.4.1.115");
            encId.setExtension(UUID.randomUUID().toString());
            encounter.getIds().add(encId);

            CE encCode = DatatypesFactory.eINSTANCE.createCE("183919006", "2.16.840.1.113883.6.96", "SNOMEDCT", null);
            encounter.setCode(encCode);

            ED text = DatatypesFactory.eINSTANCE.createED();
            text.addText("Hospice Encounter");
            encounter.setText(text);

            CS statusCode = DatatypesFactory.eINSTANCE.createCS("completed");
            encounter.setStatusCode(statusCode);

            IVL_TS effectiveTime = DatatypesFactory.eINSTANCE.createIVL_TS();
            if (StringUtils.hasText(snomedCode.getStartDate())) {
                String formattedStartDate = convertToReqdFormatDate(snomedCode.getStartDate());
                if (StringUtils.hasText(formattedStartDate)) {
                    IVXB_TS low = DatatypesFactory.eINSTANCE.createIVXB_TS();
                    low.setValue(formattedStartDate);
                    effectiveTime.setLow(low);
                }
            }

            if (StringUtils.hasText(snomedCode.getEndDate())) {
                String formattedEndDate = convertToReqdFormatDate(snomedCode.getEndDate());
                if (StringUtils.hasText(formattedEndDate)) {
                    IVXB_TS high = DatatypesFactory.eINSTANCE.createIVXB_TS();
                    high.setValue(formattedEndDate);
                    effectiveTime.setHigh(high);
                }
            }
            encounter.setEffectiveTime(effectiveTime);


            Author author = CDAFactory.eINSTANCE.createAuthor();
            author.getTemplateIds().add(DatatypesFactory.eINSTANCE.createII("2.16.840.1.113883.10.20.24.3.155", "2019-12-01"));

            TS authorTime = DatatypesFactory.eINSTANCE.createTS();
            if (StringUtils.hasText(snomedCode.getEndDate())) {
                String formattedEndDate = convertToReqdFormatDate(snomedCode.getEndDate());
                if (StringUtils.hasText(formattedEndDate)) {
                    authorTime.setValue(formattedEndDate);
                }
            }
            author.setTime(authorTime);

            AssignedAuthor assignedAuthor = CDAFactory.eINSTANCE.createAssignedAuthor();
            assignedAuthor.getIds().add(DatatypesFactory.eINSTANCE.createII(NullFlavor.NA));
            author.setAssignedAuthor(assignedAuthor);
            encounter.getAuthors().add(author);

            entry.setEncounter(encounter);
            return entry;
        } catch (Exception e) {
            log.error("Error creating hospice encounter entry for 183919006: {}", e.getMessage());
            return null;
        }
    }

    private String convertToReqdFormatDate(String isoDate) {
        if (!StringUtils.hasText(isoDate)) {
            return null;
        }
        try {
            java.time.Instant instant = java.time.Instant.parse(isoDate);
            java.time.LocalDateTime localDateTime = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
            return localDateTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        } catch (Exception e) {
            log.error("Error converting ISO date to HL7 format: {}", e.getMessage());
            return null;
        }
    }

    private String getCurrentTimestamp() {
        ZonedDateTime now = ZonedDateTime.now();
        return now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + now.format(DateTimeFormatter.ofPattern("Z")).replace(":", "");
    }

    public String generateXml(ClinicalDocument document) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            CDAUtil.save(document, outputStream);
            String xml = outputStream.toString(StandardCharsets.UTF_8);

            if (xml.contains("<ClinicalDocument")) {
                xml = xml.replaceFirst("<ClinicalDocument([^>]*)>", "<ClinicalDocument$1 xmlns:voc=\"urn:hl7-org:v3/voc\" xmlns:sdtc=\"urn:hl7-org:sdtc\">");
            }
            return xml;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String addSdtcValueSetForTargetCodeSystem(String xml) {
        if (xml == null || xml.isEmpty()) {
            return xml;
        }
        try {
            if (xml.contains("<ClinicalDocument") && !xml.contains("xmlns:sdtc=\"urn:hl7-org:sdtc\"")) {
                xml = xml.replaceFirst("<ClinicalDocument([^>]*)>", "<ClinicalDocument$1 xmlns:sdtc=\"urn:hl7-org:sdtc\">");
            }

            String originalXml = xml;
            if (xml.contains("32485007") && xml.contains("Encounter Inpatient") && !xml.contains("sdtc:dischargeDispositionCode")) {
                String encounterPattern = "(?s)(<encounter[^>]*classCode=\"ENC\"[^>]*moodCode=\"EVN\"[^>]*>.*?<code[^>]*code=\"32485007\"[^>]*codeSystem=\"2\\.16\\.840\\.1\\.113883\\.6\\.96\"[^>]*codeSystemName=\"SNOMEDCT\"[^>]*/>.*?<text[^>]*>Encounter Inpatient</text>.*?<statusCode[^>]*code=\"completed\"[^>]*/>.*?<effectiveTime>.*?</effectiveTime>)(.*?</encounter>)";
                String replacement = "$1<sdtc:dischargeDispositionCode code=\"428371000124100\" codeSystem=\"2.16.840.1.113883.6.96\" codeSystemName=\"SNOMEDCT\"/>$2";
                xml = xml.replaceAll(encounterPattern, replacement);
                
                if (originalXml.equals(xml)) {
                    String flexiblePattern = "(?s)(<encounter[^>]*>.*?<code[^>]*code=\"32485007\"[^>]*/>.*?<text[^>]*>Encounter Inpatient</text>.*?)(</encounter>)";
                    String flexibleReplacement = "$1<sdtc:dischargeDispositionCode code=\"428371000124100\" codeSystem=\"2.16.840.1.113883.6.96\" codeSystemName=\"SNOMEDCT\"/>$2";
                    xml = xml.replaceAll(flexiblePattern, flexibleReplacement);
                }
            }

            return xml;
        } catch (Exception e) {
            e.printStackTrace();
            return xml;
        }
    }

    public String generateXmlWithValueSetFix(ClinicalDocument document) {
        String xml = generateXml(document);
        return addSdtcValueSetForTargetCodeSystem(xml);
    }

    private String convertEpochToStandardTime(long epochTime) {
        try {
            java.util.Date date = new java.util.Date(epochTime * 1000);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
            return sdf.format(date);
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isInInitialPopulation(PersonalDetailsData patientData, List<Appointment> appointments, String measurementPeriodStart, String measurementPeriodEnd) {
        if (patientData == null || patientData.getResponse() == null || CollectionUtils.isEmpty(patientData.getResponse().getPatientInformation())) {
            return false;
        }

        LocalDate periodStart = LocalDate.parse(measurementPeriodStart);
        LocalDate periodEnd = LocalDate.parse(measurementPeriodEnd);

        LocalDate dateOfBirth = null;
        Map<String, PatientInformation> patientInfoMap = patientData.getResponse().getPatientInformation();
        PatientInformation patientInfo = patientInfoMap.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
        if (patientInfo != null && StringUtils.hasText(patientInfo.getBirthDate())) {
            try {
                dateOfBirth = LocalDate.parse(patientInfo.getBirthDate());
            } catch (Exception e) {
                return false;
            }
        } else {
            return false;
        }

        int ageAtStart = Period.between(dateOfBirth, periodStart).getYears();
        if (ageAtStart < 65) {
            return false;
        }

        if (CollectionUtils.isEmpty(appointments)) {
            return false;
        }

        ZoneId zoneId = ZoneId.systemDefault();
        boolean hasVisitInPeriod = appointments.stream().filter(Objects::nonNull).map(appt -> {
                    String startEpoch = appt.getDate_time();
                    String endEpoch = appt.getEnd_date_time();
                    LocalDate startDate = null;
                    LocalDate endDate = null;
                    try {
                        if (StringUtils.hasText(startEpoch)) {
                            startDate = new java.util.Date(Long.parseLong(startEpoch) * 1000L).toInstant().atZone(zoneId).toLocalDate();
                        }
                    } catch (Exception e) {
                    }
                    try {
                        if (StringUtils.hasText(endEpoch)) {
                            endDate = new java.util.Date(Long.parseLong(endEpoch) * 1000L).toInstant().atZone(zoneId).toLocalDate();
                        }
                    } catch (Exception e) {
                    }

                    return startDate != null ? startDate : endDate;
                })
                .filter(Objects::nonNull)
                .anyMatch(date -> !date.isBefore(periodStart) && !date.isAfter(periodEnd));

        return hasVisitInPeriod;
    }

    private List<LoincCode> parseLoincCodes(Object loincCodesNode) {
        List<LoincCode> result = new ArrayList<>();
        if (loincCodesNode == null) {
            return result;
        }

        try {
            if (loincCodesNode instanceof List<?>) {
                List<Object> nodeList = (List<Object>) loincCodesNode;

                for (Object item : nodeList) {
                    try {
                        LoincCode code = objectMapper.convertValue(item, LoincCode.class);
                        if (code != null) {
                            result.add(code);
                        }
                    } catch (IllegalArgumentException ignore) {}
                }
            }

        } catch (Exception e) {}
        return result;
    }


    private List<SnomedCode> parseSnomedCodes(Object snomedCodesNode) {
        List<SnomedCode> result = new ArrayList<>();
        if (snomedCodesNode == null) {
            return result;
        }
        try {
            if (snomedCodesNode instanceof List<?>) {
                List<Object> node = (List<Object>) snomedCodesNode;
                if (!node.isEmpty()) {
                    for (Object item : node) {
                        try {
                            SnomedCode code = objectMapper.convertValue(item, SnomedCode.class);
                            if (code != null) {
                                result.add(code);
                            }
                        } catch (IllegalArgumentException ignore) {}
                    }
                }
            }
        } catch (Exception e) {}
        return result;
    }

}



