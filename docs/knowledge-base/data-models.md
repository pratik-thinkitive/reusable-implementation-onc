# Data Models

Two families: **persisted G2 entities** (JPA/PostgreSQL) and **EHR transport DTOs** (Jackson, not persisted).

---

## G2 — Persisted Entities
[G2/entity/](src/main/java/com/onc/G2/entity/) · repositories in [G2/repository/](src/main/java/com/onc/G2/repository/). Lombok `@Data`, `@JsonIgnoreProperties({"hibernateLazyInitializer","handler"})`. **No Flyway/Liquibase and no `ddl-auto`** — both tables must be created out of band.

### `patient_access_request` — `PatientAccessRequest`
| Column | Java type | Notes |
|---|---|---|
| `id` | `Long` | identity |
| `first_name`, `last_name`, `patient_id` | `String` | copied from the EHR at request time |
| `patient_fhir_id` | `String` | not null; composite `Org-Patient` |
| `organisation_id` | `Integer` | from EHR attribution |
| `provider_id`, `tin_id` | `String` | from the **caller's** query params, not the lookup |
| `request_type` | `RequestType` | `@Enumerated(STRING)`, not null |
| `status` | `RequestStatus` | `@Enumerated(STRING)`, not null |
| `requested_at`, `access_granted_at`, `access_revoked_at` | `Instant` | `requested_at` not null |
| `reporting_period_start` / `_end` | `LocalDate` | |
| `is_first_encounter` | `Boolean` | always written as `null` by the workflow |
| `encounter_id` | `String` | duplicate-guard key |

No `@PrePersist`; `requestedAt` is set by the service.

### `patient_access_data` — `PatientAccessData`
Aggregated measure state, **one row per patient per reporting period**.

| Column | Java type | Notes |
|---|---|---|
| `id` | `Long` | identity |
| `first_name`, `last_name`, `patient_id`, `organisation_id`, `provider_id`, `tin_id` | — | attribution copy; **left null** when the row is created by `getOrCreateMetrics` |
| `patient_fhir_id` | `String` | not null |
| `reporting_period_start` / `_end` | `LocalDate` | not null |
| `denominator_count`, `numerator_count` | `Integer` | not null, default `0`; both are 0-or-1 flags in practice |
| `has_access_granted` | `Boolean` | |
| `access_granted_date`, `access_revoked_date`, `first_encounter_date` | `Instant` | |
| `is_numerator_recorded` | `Boolean` | sticky — keeps a revoked patient visible on dashboards |
| `created_at`, `updated_at` | `Instant` | not null, set by `@PrePersist` / `@PreUpdate` |

### Enums — [G2/enums/](src/main/java/com/onc/G2/enums/)
| Enum | Constants | Notes |
|---|---|---|
| `RequestStatus` | `PENDING`, `ACCESS_GRANTED`, `ACCESS_REVOKED` | persisted by name; also a **string literal** in `findCurrentActiveAccess` JPQL |
| `RequestType` | `MEDICAL_DETAILS_ACCESS`, `PERSONAL_DETAILS_ACCESS` | persisted by name; only the first is used |

### Repository queries
| Repo | Method | Predicate |
|---|---|---|
| `PatientAccessRequestRepository` | `findCurrentActiveAccess` | patient + type + `status='ACCESS_GRANTED'` + granted not null + revoked null — **provider/TIN agnostic** |
| | `findByOrganisationIdAndProviderIdAndTinIdAndStatus` | exact match on all four |
| | `findByPatientFhirIdAndEncounterIdAndRequestType` | `Optional` — duplicate-encounter guard |
| | `findByPatientFhirIdAndRequestTypeAndProviderIdAndTinId` | `Optional` — but the columns are not unique |
| `PatientAccessDataRepository` | `findByPatientFhirIdAndReportingPeriodStartAndReportingPeriodEnd` | `Optional` — the one-row-per-period key |
| | `findAllByTinAndProviderWithinPeriod`, `findByTinIdWithinReportingPeriod`, `getAccessGrantedPatients*` | **overlap** (`start <= :end AND end >= :start`) |
| | `findAllPatientsWithinReportingPeriod` | **containment** (`start <= :start AND end >= :end`) — inconsistent with the rest |

### G2 DTOs — [G2/dto/](src/main/java/com/onc/G2/dto/)
All of these are payloads carried in `ApiResponse.data`; `success`/`message`/`status` fields that used to duplicate the envelope have been removed.

| DTO | Purpose |
|---|---|
| `PatientAccessRequestDto` | entity mirror + `duplicateRequest` / `duplicateMessage` carrying the 409 condition |
| `PatientAccessDataDto` | entity mirror + calculated `percentage` |
| `AccessDashboardResponse` | `@JsonPropertyOrder` + `NON_NULL`: `groupId` (group dashboard only), `patientsWithAccess`, period, `totalNumerator`, `totalDenominator`, `percentage` |
| `AccessRequestResult` | the `request-access` payload: `requestId` + derived `status` (`PENDING` \| `DUPLICATE`). Its `outcome` and `message` are `@JsonIgnore` — the controller maps them to the envelope's code and message |
| `PatientAttribution` | name + `organisationId` + `providerId` + `tinId`; **all-null instance on lookup failure, never an exception** |
| `ReportingPeriod` (model) | `record(start, end)` — `currentCalendarYear()`, `of()` (per-end defaulting) |

Removed: `AccessRequestResponse` (its `success`/`message`/`requestId`/`status` are envelope fields) and `AccessDeniedResponse` (a 403 with code `PATIENT_ACCESS_DENIED` says the same thing; its `accessGranted` was implied by `success:false` and its `requestType` was a constant for that endpoint).

---

## EHR — Transport DTOs
[EHR/dto/](src/main/java/com/onc/EHR/dto/) — 62 classes, plain Lombok `@Data` POJOs. Not persisted.

### Envelope Pattern
Provider responses wrap payloads uniformly: `{ code: int, data: <T>, message: String }`.

| Envelope | Payload |
|---|---|
| `MedicalDetailsResponse` | `MedicalDetailsData` |
| `PersonalDetailsResponse` | `PersonalDetailsData` |
| `InsuranceDetailsResponse` | `List<InsuranceDetails>` |
| `AppointmentResponse` | `AppointmentData` |
| `DoctorDetailsResponse` | `DoctorDetailsData` |
| `FormDataResponse` | `FormData` |
| `SoapContextResponse` | `SoapContextData` → `List<SoapContext>` |
| `ClinicResponse` | `Clinic` |
| `ClinicListResponse` | `ClinicDataWrapper` → `total`/`pages`/`current`/`no_of_records` + `List<Clinic>` |
| `DoctorListResponse` | `DoctorDataWrapper` → same paging shape + `List<DoctorDetailsData>` |

### Core Aggregates
| Model | Key fields | Maps to CDA / FHIR |
|---|---|---|
| `PersonalDetailsData` | submissionId, patientId, organisationId, createdBy, appointmentId, formName, metadata, response, spryCaseId, specialty | `recordTarget`/`author`/`custodian`; also the source of G2 attribution — FHIR **Patient** |
| `PersonalDetailsResponseBlock` | `Map<String, PatientInformation> patientInformation` | keyed map; code takes `.values().stream().findFirst()` |
| `PatientInformation` | firstName, lastName, birthDate, gender, race, ethnicity, address, phone, careTeam | FHIR **Patient** |
| `MedicalDetailsData` | submission_id, patient_id, form_name, version, metadata, `Map<String,Object> response` | untyped response map — not consumed by QRDA generation |
| `MedicalDetails` | 15 keyed section maps (below) | typed counterpart; **not wired into the QRDA path** |
| `FormData` | submissionId, patientId, appointmentId, formName, `FormResponse response`, `FormMetadata metadata` | SOAP assessment forms — source of QRDA entries |
| `FormResponse` | `Map<String, CodeSection> assessment` / `intervention` | JSON keys `"Assessment"` / `"Intervention"` |
| `CodeSection` | `Object snomedCodes`, `Object loincCodes` | typed lazily via `parseLoincCodes` / `parseSnomedCodes` |
| `Appointment` | appointment_id, patient_id, clinic_id, date_time / end_date_time (**epoch seconds as String**), status, type, categories | FHIR **Encounter** → CDA Encounter Performed |
| `InsuranceDetails` | insurance_card_id, `InsurancePayer`, number, plan_type, plan dates, payer_type | FHIR **Coverage** → Patient Characteristic Payer |
| `DoctorDetailsData` | doctor_id, name parts, npi, license, cms_certificate_number, tax_id_number, taxonomy_code, `List<Clinic> clinics` | FHIR **Practitioner** / **Organization** |
| `Clinic` | clinic_id, `tax_identification_number` (the G2 TIN), address | FHIR **Organization** |
| `SoapContext` | assessmentSubmissionId (+ context ids) | fan-out key for per-context form-data fetches |

### Terminology Models
| Model | Fields | Code system |
|---|---|---|
| `LoincCode` | code, description, id, start_date, end_date, status | LOINC `2.16.840.1.113883.6.1` |
| `SnomedCode` | code, description, id, dates, status | SNOMED CT `2.16.840.1.113883.6.96` |
| `LoincCodesDeserializer` | custom `JsonDeserializer<List<LoincCode>>` | tolerates non-list/absent nodes |

Codes arrive prefixed in JSON (e.g. `SCT-32485007`, `LOINC-…`) and are matched by literal string comparison in `addPatientDataSection`.

### MedicalDetails Section Map (`@JsonProperty` keys — exact strings)
`"Medical Details"`→ImmunizationEntry · `"Vitals"`→VitalsEntry · `"Allergy"`→AllergyEntry · `"Medication"`→MedicationEntry · `"Smoking Status"`→SmokingStatusEntry · `"Laboratory Test"`→LaboratoryTestEntry · `"Laboratory Values/Results"`→LabResultEntry · `"Clinican Test and Clinical Result"` *(sic)*→ClinicalTestEntry · `"Diagnostic Imaging Report "` *(trailing space)*→ImagingReportEntry · `"Implantable Device"`→ImplantableDeviceEntry · `"Screening Assessment"`→ScreeningAssessmentEntry · `"Problems"`→ProblemEntry · `"Procedures"`→ProcedureEntry · `"Pregnancy Status"`→PregnancyStatusEntry · `"Heath Concern"` *(sic)*→HealthConcernEntry

Each is `Map<String, XxxEntry>` keyed by an opaque form-instance id. Every entry field is `String` — dates, doses, and statuses are unparsed text.

### Naming Inconsistency
Two conventions coexist: `snake_case` Java fields mirroring the API (`Appointment`, `InsuranceDetails`, `DoctorDetailsData`, `Clinic`, `Metadata`) vs. `camelCase` + explicit `@JsonProperty` (`FormData`, `PersonalDetailsData`, `MetaDataPD`, `LoincCode`). `Metadata` and `MetaDataPD` are near-duplicates of the same upstream object. G2 DTOs are uniformly `camelCase`.
