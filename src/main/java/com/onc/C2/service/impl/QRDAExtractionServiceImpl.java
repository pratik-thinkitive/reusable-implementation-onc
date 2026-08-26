package com.onc.C2.service.impl;

import com.onc.C2.dto.*;
import com.onc.C2.service.QRDAExtractionService;
import com.onc.api.support.ResponseCode;
import com.onc.common.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.*;

@Slf4j
@Service
public class QRDAExtractionServiceImpl implements QRDAExtractionService {

    @Override
    public PatientData extractPatientData(InputStream xmlInput) {

        Document doc = toDocument(xmlInput);

        Node patientNode = doc.getElementsByTagName("patient").item(0);
        Node patientRoleNode = doc.getElementsByTagName("patientRole").item(0);

        if (patientNode == null || patientRoleNode == null) {
            throw new AppException(ResponseCode.BAD_REQUEST, "The document has no patientRole, so it is not a QRDA Category I file.");
        }

        Element patient = (Element) patientNode;
        Element patientRole = (Element) patientRoleNode;

        String given = getText(patient, "given");
        String family = getText(patient, "family");
        String birthTime = getAttr(patient, "birthTime", "value");
        String genderCode = getAttr(patient, "administrativeGenderCode", "code");
        String raceCode = getAttr(patient, "raceCode", "code");
        String ethnicityCode = getAttr(patient, "ethnicGroupCode", "code");

        Element addr = (Element) patientRole.getElementsByTagName("addr").item(0);
        String street = getText(addr, "streetAddressLine");
        String city = getText(addr, "city");
        String state = getText(addr, "state");
        String postalCode = getText(addr, "postalCode");
        String country = getText(addr, "country");

        String phone = getAttrLike(patientRole, "telecom", "value", "tel:");
        String email = getAttrLike(patientRole, "telecom", "value", "mailto:");

        List<Encounter> encounters = new ArrayList<>();
        NodeList encounterNodes = doc.getElementsByTagName("encounter");
        for (int i = 0; i < encounterNodes.getLength(); i++) {
            Element encounter = (Element) encounterNodes.item(i);
            if (!"ENC".equals(encounter.getAttribute("classCode"))) continue;

            String id = getAttr(encounter, "id", "extension");
            String code = getAttr(encounter, "code", "code");
            String codeSystem = getAttr(encounter, "code", "codeSystem");
            String codeSystemName = getAttr(encounter, "code", "codeSystemName");
            String description = getText(encounter, "text");

            Element effectiveTime = (Element) encounter.getElementsByTagName("effectiveTime").item(0);
            String start = getAttr(effectiveTime, "low", "value");
            String end = getAttr(effectiveTime, "high", "value");
            String status = getAttr(encounter, "statusCode", "code");

            encounters.add(new Encounter(id, code, codeSystem, codeSystemName, description, start, end, status));
        }

        List<Insurance> insurances = new ArrayList<>();
        NodeList payerNodes = doc.getElementsByTagName("observation");
        for (int i = 0; i < payerNodes.getLength(); i++) {
            Element obs = (Element) payerNodes.item(i);
            String code = getAttr(obs, "code", "code");
            if ("48768-6".equals(code)) {
                String id = getAttr(obs, "id", "root");
                String payerCode = "";
                Element valueEl = (Element) obs.getElementsByTagName("value").item(0);
                if (valueEl != null) {
                    payerCode = valueEl.getAttribute("code");
                }

                Element eff = (Element) obs.getElementsByTagName("effectiveTime").item(0);
                String start = getAttr(eff, "low", "value");
                String end = getAttr(eff, "high", "value");

                insurances.add(new Insurance(id, payerCode, start, end));
            }
        }

        List<Assessment> assessments = new ArrayList<>();
        NodeList obsNodes = doc.getElementsByTagName("observation");
        for (int i = 0; i < obsNodes.getLength(); i++) {
            Element obs = (Element) obsNodes.item(i);

            NodeList templateIds = obs.getElementsByTagName("templateId");
            boolean isAssessmentPerformed = false;
            for (int j = 0; j < templateIds.getLength(); j++) {
                Element templateIdEl = (Element) templateIds.item(j);
                if ("2.16.840.1.113883.10.20.24.3.144".equals(templateIdEl.getAttribute("root"))) {
                    isAssessmentPerformed = true;
                    break;
                }
            }

            if (isAssessmentPerformed) {
                String id = getAttr(obs, "id", "extension");
                String code = getAttr(obs, "code", "code");
                String statusCode = getAttr(obs, "statusCode", "code");
                String time = getAttr(obs, "effectiveTime", "value");

                assessments.add(new Assessment(id, code, time, statusCode));
            }
        }

        List<Intervention> interventions = new ArrayList<>();

        NodeList actNodes = doc.getElementsByTagName("act");
        for (int i = 0; i < actNodes.getLength(); i++) {
            Element act = (Element) actNodes.item(i);
            String classCode = act.getAttribute("classCode");

            if ("ACT".equals(classCode)) {
                NodeList templateIds = act.getElementsByTagName("templateId");
                boolean isIntervention = false;
                for (int j = 0; j < templateIds.getLength(); j++) {
                    Element templateIdEl = (Element) templateIds.item(j);
                    String root = templateIdEl.getAttribute("root");
                    if ("2.16.840.1.113883.10.20.24.3.31".equals(root) || // Intervention Order (V4)
                            "2.16.840.1.113883.10.20.24.3.32".equals(root)) { // Intervention Performed
                        isIntervention = true;
                        break;
                    }
                }

                if (isIntervention) {
                    String id = getAttr(act, "id", "extension");
                    String code = getAttr(act, "code", "code");
                    String status = getAttr(act, "statusCode", "code");

                    Element effectiveTime = (Element) act.getElementsByTagName("effectiveTime").item(0);
                    String start = getAttr(effectiveTime, "low", "value");
                    String end = getAttr(effectiveTime, "high", "value");

                    interventions.add(new Intervention(id, code, status, start, end));
                }
            }
        }

        NodeList interventionObsNodes = doc.getElementsByTagName("observation");
        for (int i = 0; i < interventionObsNodes.getLength(); i++) {
            Element obs = (Element) interventionObsNodes.item(i);

            Element codeEl = (Element) obs.getElementsByTagName("code").item(0);
            if (codeEl == null) continue;

            String codeSystem = codeEl.getAttribute("codeSystem");
            String code = codeEl.getAttribute("code");

            if ("2.16.840.1.113883.6.96".equals(codeSystem)) {

                String status = getAttr(obs, "statusCode", "code");
                Element effectiveTime = (Element) obs.getElementsByTagName("effectiveTime").item(0);
                String start = getAttr(effectiveTime, "low", "value");
                String end = getAttr(effectiveTime, "high", "value");

                String id = getAttr(obs, "id", "root");

                interventions.add(new Intervention(id, code, status, start, end));
            }
        }

        List<Provider> providers = new ArrayList<>();
        NodeList perfNodes = doc.getElementsByTagName("performer");
        for (int i = 0; i < perfNodes.getLength(); i++) {
            Element perf = (Element) perfNodes.item(i);
            Element assignedEntity = (Element) perf.getElementsByTagName("assignedEntity").item(0);
            if (assignedEntity == null) continue;

            String npi = getIdByRoot(assignedEntity, "2.16.840.1.113883.4.6");
            String tin = getIdByRoot(assignedEntity, "2.16.840.1.113883.4.336");

            String ccn = getIdByRoot(assignedEntity, "2.16.840.1.113883.4.2");
            if (ccn.isEmpty()) {
                Element representedOrganization = (Element) assignedEntity.getElementsByTagName("representedOrganization").item(0);
                if (representedOrganization != null) {
                    ccn = getIdByRoot(representedOrganization, "2.16.840.1.113883.4.2");
                }
            }

            Element codeEl = (Element) assignedEntity.getElementsByTagName("code").item(0);
            String taxonomyCode = codeEl != null ? codeEl.getAttribute("code") : "";

            String providerGivenName = "";
            String providerFamilyName = "";
            Element assignedPerson = (Element) assignedEntity.getElementsByTagName("assignedPerson").item(0);
            if (assignedPerson != null) {
                Element nameEl = (Element) assignedPerson.getElementsByTagName("name").item(0);
                if (nameEl != null) {
                    providerGivenName = getText(nameEl, "given");
                    providerFamilyName = getText(nameEl, "family");
                }
            }

            Element addrEl = (Element) assignedEntity.getElementsByTagName("addr").item(0);
            String streetAddr = getText(addrEl, "streetAddressLine");
            String cityAddr = getText(addrEl, "city");
            String stateAddr = getText(addrEl, "state");
            String postalAddr = getText(addrEl, "postalCode");
            String countryAddr = getText(addrEl, "country");

            providers.add(new Provider(providerGivenName, providerFamilyName, npi, tin, ccn,
                    taxonomyCode, streetAddr, cityAddr, stateAddr, postalAddr, countryAddr));
        }

        String documentId = getAttr(doc.getDocumentElement(), "id", "root");
        String creationTime = getAttr(doc.getDocumentElement(), "effectiveTime", "value");
        String title = getText(doc.getDocumentElement(), "title");

        PatientData data = new PatientData();
        data.setFirstName(given);
        data.setLastName(family);
        data.setDateOfBirth(formatDate(birthTime));
        data.setGender(genderCode);
        data.setRace(raceCode);
        data.setEthnicity(ethnicityCode);
        data.setStreetAddress(street);
        data.setCity(city);
        data.setState(state);
        data.setPostalCode(postalCode);
        data.setCountry(country);
        data.setEncounters(encounters);
        data.setInsurances(insurances);
        data.setProviders(providers);
        data.setAssessments(assessments);
        data.setInterventions(interventions);
        data.setDocumentId(documentId);
        data.setDocumentTitle(title);
        data.setCreationTime(creationTime);

        return data;
    }

    /**
     * Parses caller-supplied XML with external entities and DTDs turned off.
     *
     * <p>This endpoint accepts a document from outside, so an unhardened parser would resolve
     * whatever entities that document declared - reading local files or reaching internal hosts
     * on the caller's behalf.
     *
     * <p>Namespace awareness stays off, matching the tag names looked up below. QRDA carries CDA
     * in the default namespace, so unprefixed lookups resolve; anything under a prefix, such as
     * the {@code sdtc} extensions, would need the prefix included in the tag name.
     */
    private Document toDocument(InputStream xmlInput) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xmlInput);
            doc.getDocumentElement().normalize();
            return doc;

        } catch (Exception e) {
            log.warn("Rejected an unparseable QRDA import", e);
            throw new AppException(ResponseCode.BAD_REQUEST,
                    "The document could not be read as XML.", e);
        }
    }

    private String getText(Element parent, String tag) {
        if (parent == null) return "";
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() > 0 && nodes.item(0).getNodeType() == Node.ELEMENT_NODE) {
            return nodes.item(0).getTextContent().trim(); // Added trim for cleaner output
        }
        return "";
    }

    private String getAttr(Element parent, String tag, String attr) {
        if (parent == null) return "";
        Node node = parent.getElementsByTagName(tag).item(0);
        if (node != null && node instanceof Element) {
            return ((Element) node).getAttribute(attr);
        }
        return "";
    }

    private String getAttrLike(Element parent, String tag, String attr, String prefix) {
        if (parent == null) return "";
        NodeList nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String val = el.getAttribute(attr);
            if (val != null && val.startsWith(prefix)) {
                return val.replace(prefix, "");
            }
        }
        return "";
    }

    private String getIdByRoot(Element parent, String root) {
        if (parent == null) return "";
        NodeList ids = parent.getElementsByTagName("id");
        for (int i = 0; i < ids.getLength(); i++) {
            Element el = (Element) ids.item(i);
            if (root.equals(el.getAttribute("root"))) {
                String extension = el.getAttribute("extension");
                if (extension != null && !extension.isEmpty()) {
                    return extension;
                }
            }
        }
        return "";
    }

    @Override
    public String formatDate(String value) {
        if (value != null && value.length() >= 8) {
            return value.substring(0, 4) + "-" + value.substring(4, 6) + "-" + value.substring(6, 8);
        }
        return value;
    }
}