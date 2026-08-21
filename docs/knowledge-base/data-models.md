# Data Models

All DTOs: [src/main/java/com/onc/qrdaC1/QRDA/dto/](src/main/java/com/onc/qrdaC1/QRDA/dto/) — 57 classes, package `com.spry.ehr.QRDA.dto`. All are plain Lombok `@Data` POJOs (a few `@Builder`). **No JPA entities, no repositories, no database tables** despite the JPA/PostgreSQL dependencies.

## Envelope Pattern
Spry API responses wrap payloads uniformly: `{ code: int, data: <T>, message: String }`.

| Envelope | Payload |
|---|---|
| `MedicalDetailsResponse` | `MedicalDetailsData` |
| `PersonalDetailsResponse` | `PersonalDetailsData` |
| `InsuranceDetailsResponse` | `List<InsuranceDetails>` |
| `AppointmentResponse` | `AppointmentData` |
| `DoctorDetailsResponse` | `DoctorDetailsData` |
| `FormDataResponse` | `FormData` |
| `SoapContextResponse` | `SoapContextData` → `List<SoapContext>` |

## Core Aggregates
| Model | Key fields | Maps to CDA / FHIR |
|---|---|---|
| `PersonalDetailsData` | submissionId, patientId, organisationId, appointmentId, formName, metadata (`MetaDataPD`), response (`PersonalDetailsResponseBlock`), spryCaseId, specialty | drives `recordTarget`, `author`, `custodian`, `legalAuthenticator`, `documentationOf` — FHIR **Patient** |
| `PersonalDetailsResponseBlock` | `Map<String, PatientInformation> patientInformation` | keyed map; code takes `.values().stream().findFirst()` |
| `PatientInformation` | firstName, lastName, birthDate, gender, race, ethnicity, address, phone, careTeam | FHIR **Patient** |
| `MedicalDetailsData` | submission_id, patient_id, form_name, version, metadata, `Map<String,Object> response` | untyped response map — not consumed by QRDA generation |
| `MedicalDetails` | 15 keyed section maps (see below) | typed counterpart of the above; **not wired into the QRDA path** |
| `FormData` | submissionId, patientId, appointmentId, formName, `FormResponse response`, `FormMetadata metadata` | SOAP assessment forms — source of QRDA entries |
| `FormResponse` | `Map<String, CodeSection> assessment`, `Map<String, CodeSection> intervention` | JSON keys `"Assessment"` / `"Intervention"` |
| `CodeSection` | `Object snomedCodes` (`snomed_codes`), `Object loincCodes` (`loinc_codes`) | typed lazily via `parseLoincCodes` / `parseSnomedCodes` |
| `Appointment` | appointment_id, patient_id, clinic_id, date_time / end_date_time (**epoch seconds as String**), appointment_status, type, `List<AppointmentCategory> category`, untyped clinic/doctor/patient maps | FHIR **Encounter** → CDA Encounter Performed |
| `InsuranceDetails` | insurance_card_id, `InsurancePayer insurance_payer`, insurance_number, plan_type, plan dates, payer_type | FHIR **Coverage** → Patient Characteristic Payer |
| `DoctorDetailsData` | doctor_id, name parts, npi, license, cms_certificate_number, tax_id_number, taxonomy_code, `List<Clinic> clinics` | FHIR **Practitioner** / **Organization** |
| `SoapContext` | assessmentSubmissionId (+ context ids) | fan-out key for per-context form-data fetches |

## Terminology Models
| Model | Fields | Code system |
|---|---|---|
| `LoincCode` | code, description, id, start_date, end_date, status | LOINC `2.16.840.1.113883.6.1` |
| `SnomedCode` | code, description, id, dates, status | SNOMED CT `2.16.840.1.113883.6.96` |
| `LoincCodesDeserializer` | custom `JsonDeserializer<List<LoincCode>>` | tolerates non-list/absent nodes |

Codes arrive prefixed in JSON (e.g. `SCT-32485007`, `LOINC-…`) and are matched by literal string comparison in `addPatientDataSection`.

## MedicalDetails Section Map (`@JsonProperty` keys — exact strings)
`"Medical Details"`→ImmunizationEntry · `"Vitals"`→VitalsEntry · `"Allergy"`→AllergyEntry · `"Medication"`→MedicationEntry · `"Smoking Status"`→SmokingStatusEntry · `"Laboratory Test"`→LaboratoryTestEntry · `"Laboratory Values/Results"`→LabResultEntry · `"Clinican Test and Clinical Result"` *(sic)*→ClinicalTestEntry · `"Diagnostic Imaging Report "` *(trailing space)*→ImagingReportEntry · `"Implantable Device"`→ImplantableDeviceEntry · `"Screening Assessment"`→ScreeningAssessmentEntry · `"Problems"`→ProblemEntry · `"Procedures"`→ProcedureEntry · `"Pregnancy Status"`→PregnancyStatusEntry · `"Heath Concern"` *(sic)*→HealthConcernEntry

Each is `Map<String, XxxEntry>` keyed by an opaque form-instance id. Every entry field is `String` — dates, doses, and statuses are unparsed text; `List<GenericItem>` carries lookup-style values (`itemName`, `id`, `valueRefId`, `name`, `associatedFields`).

## Naming Inconsistency
Two conventions coexist: `snake_case` Java fields mirroring the API (`Appointment`, `InsuranceDetails`, `DoctorDetailsData`, `MedicalDetailsData`, `Metadata`) vs. `camelCase` + explicit `@JsonProperty` (`FormData`, `PersonalDetailsData`, `MetaDataPD`, `CareTeamMember`, `LoincCode`). `Metadata` and `MetaDataPD` are near-duplicates of the same upstream object.
