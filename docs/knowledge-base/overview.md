# Overview — ONC (reusable-implementation-onc)

## Purpose
Spring Boot REST service implementing three ONC/CMS reporting concerns over a shared, vendor-neutral EHR read layer. Module names follow the certification criteria they serve:
- **C1** — §170.315(c)(1): generates **QRDA Category I** XML for eCQM **CMS139** (Falls: Screening for Future Fall Risk, ≥65), singly or as a ZIP batch.
- **C2C3** — §170.315(c)(2) import-and-calculate and (c)(3) reporting: takes a ZIP of Category I documents, deduplicates and uploads the patients, evaluates the measure, and emits an aggregated **QRDA Category III** summary.
- **G2** — patient-access measure ("Provide Patients Electronic Access"): patients request access to their own health information, admins grant/revoke, and the service keeps numerator/denominator per patient per reporting period.

## Tech Stack
| Layer | Choice | Version |
|---|---|---|
| Language | Java | 17 |
| Framework | Spring Boot (`spring-boot-starter-webmvc`) | 4.1.0 |
| Build | Maven (wrapper `mvnw`), artifact `com.onc:qrdaC1` (name predates the C2C3 module) | — |
| CDA generation | `org.openehealth.ipf.oht.mdht:ipf-oht-mdht-uml-cda` (MDHT) | 1.2.0.201212201425 |
| FHIR | `ca.uhn.hapi.fhir:hapi-fhir-structures-r4` | 6.4.0 (declared, unused in code) |
| Persistence | `spring-boot-starter-data-jpa` + PostgreSQL | **in use** — 2 entities, 2 repositories (G2 only) |
| Boilerplate | Lombok (`@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j`) | — |
| Validation | `spring-boot-starter-validation` | declared, **no constraints used** |
| Test | `*-test` starters, JUnit 5, MockMvc | 5 classes / 41 tests, all passing |

## Architecture
```
                    ┌──────────────────────────────────────────────┐
HTTP /ehr/cms/qrda  │ C1: QRDACMSController → QRDACMSService(Impl)  │  CDA/MDHT → QRDA Cat I XML
                    └──────────────┬───────────────────────────────┘
                                   │ EHR reads delegated
HTTP /ehr/data      ┌──────────────▼───────────────────────────────┐
                    │ EHRDataController → EHRDataService(Impl)      │──► RestTemplate ──► EHR provider API
                    │                     EHRTokenService(Impl)     │──► OAuth2 password grant
                    └──────────────▲──────────────▲────────────────┘
                                   │              │
HTTP /ehr/c2        ┌──────────────┴──────────────┴────────────────┐
                    │ C2C3: QRDAIIIController                       │
                    │   → QRDAExtractionService   (Cat I  → data)   │
                    │   → QRDAAggregationService  (data → Cat III)  │
                    │   → PatientSummaryService · MeasureEvaluator  │
                    └──────────────▲───────────────────────────────┘
                                   │
HTTP /ehr/g2        ┌──────────────┴───────────────────────────────┐
HTTP /ehr/admin/…   │ G2Controller / PatientAccessAdminController   │
                    │   → PatientAccessWorkflowService  (patient)   │
                    │   → PatientAccessAdminService     (admin)     │
                    │   → PatientAccessRequestService   ─┐          │
                    │   → PatientAccessDataService      ─┼► JPA ──► PostgreSQL
                    │   → PatientAttributionService  ────┘          │
                    └──────────────────────────────────────────────┘

EHR, C1 and G2 controllers extend BaseController and answer with the ApiResponse
envelope, throwing AppException for one GlobalExceptionHandler to shape. Three
endpoints are outside it: C1's /file and /zip and C2C3's /summary return XML and
ZIPs, so only their failures are enveloped. C2C3 has not been migrated onto the
envelope at all — see patterns.md.
```

## Module Map
| Path | Contents |
|---|---|
| [ONCApplication.java](src/main/java/com/onc/ONCApplication.java) | Spring Boot entrypoint |
| [config/ConfigurationService.java](src/main/java/com/onc/config/ConfigurationService.java) | `RestTemplate` + `ObjectMapper` beans (lenient: unknown props ignored, single-value-as-array) |
| [api/support/](src/main/java/com/onc/api/support/) | `ApiResponse` envelope, `ResponseCode` (each constant owns its HTTP status), `BaseController` |
| [api/GlobalExceptionHandler.java](src/main/java/com/onc/api/GlobalExceptionHandler.java) | The one place a failure becomes a response, for every module |
| [common/](src/main/java/com/onc/common/) | `AppException` (unchecked, carries a `ResponseCode`), `AppService.throwError` |
| [EHR/controller/](src/main/java/com/onc/EHR/controller/) | `EHRDataController` — 9 endpoints under `/ehr/data` |
| [EHR/service/](src/main/java/com/onc/EHR/service/) | `EHRDataService` (384 LOC impl), `EHRTokenService` (shared `RestTemplate`) |
| [EHR/dto/](src/main/java/com/onc/EHR/dto/) | 65 DTOs + `LoincCodesDeserializer` — Jackson-mapped provider payloads, shared by C1, C2C3 and G2 |
| [C1/controller/](src/main/java/com/onc/C1/controller/) | `QRDACMSController` — 8 endpoints under `/ehr/cms/qrda` |
| [C1/service/impl/](src/main/java/com/onc/C1/service/impl/) | `QRDACMSServiceImpl` (1770 LOC) — CDA build; EHR reads are pass-throughs |
| [C2C3/controller/](src/main/java/com/onc/C2C3/controller/) | `QRDAIIIController` — 2 endpoints under `/ehr/c2` |
| [C2C3/service/](src/main/java/com/onc/C2C3/service/) | `QRDAExtractionService` (1936 LOC impl) reads Cat I; `QRDAAggregationService` (3722 LOC impl) uploads and emits Cat III; `PatientSummaryService` fetches per patient |
| [C2C3/measure/](src/main/java/com/onc/C2C3/measure/) | `MeasureEvaluator` (1136 LOC) — IPOP/DENOM/NUMER/DENEXCEP evaluation, static |
| [C2C3/dto/](src/main/java/com/onc/C2C3/dto/) | `PatientMeasureData` — patient payload plus measure results; everything else reuses `EHR/dto` |
| [G2/controller/](src/main/java/com/onc/G2/controller/) | `G2Controller` (2), `PatientAccessAdminController` (12) |
| [G2/service/](src/main/java/com/onc/G2/service/) | 5 interfaces + 5 impls (workflow, admin, request, data, attribution) |
| [G2/entity/](src/main/java/com/onc/G2/entity/) · [G2/repository/](src/main/java/com/onc/G2/repository/) | `PatientAccessRequest`, `PatientAccessData` + JPA repos |
| [G2/converter/](src/main/java/com/onc/G2/converter/) | `StringToRequestTypeConverter` — case-insensitive enum binding, so a bad value is a 400 |
| [G2/model/ReportingPeriod.java](src/main/java/com/onc/G2/model/ReportingPeriod.java) | `record(start, end)` — calendar-year defaults |

## G2 Access Flow
1. `POST /ehr/g2/request-access` → attribution looked up from EHR (name, org, TIN via doctor→clinics) → `PatientAccessRequest` saved `PENDING` → `PatientAccessData` row seeded → denominator set to 1 → **201**.
2. Duplicate guards: an existing `ACCESS_GRANTED` or `ACCESS_REVOKED` row for the same patient/provider/TIN, or any row for the same encounter → **409** `DUPLICATE_REQUEST`, carrying the blocking request's id.
3. Admin `POST /grant-access/{id}` → status `ACCESS_GRANTED`, denominator re-affirmed at `requestedAt`, numerator set to 1. `revoke-access/{id}` → numerator back to 0. A request in the wrong status → **409** `STATUS_TRANSITION_BLOCKED`; an unknown id → **404**.
4. `GET /ehr/g2/personal-details` → active-access check; on success records the view (numerator) and returns the EHR payload, else **403** `PATIENT_ACCESS_DENIED` with the advisory message.

## C2C3 Flows
**Import** — `POST /ehr/c2/import`, multipart ZIP:
1. First XML in the ZIP yields the provider; `createProvider` + `updateProviderDetails` register them.
2. Every XML is parsed to `ExtractedQrdaData` (demographics, encounters, coverage, providers, assessments, interventions).
3. `detectAndMergeDuplicates` groups patients by fuzzy demographic match (name similarity via Levenshtein, DOB proximity, clinical overlap) and merges each group.
4. Each merged patient is uploaded in sequence: patient → insurance → personal-details form → case → appointments → SOAP context → SOAP details.

**Summary** — `POST /ehr/c2/summary`, body `List<String>` patientIds + measurement period:
1. `PatientSummaryService.fetchPatients` reads each patient back from the EHR.
2. `MeasureEvaluator.evaluateC2Measure` computes the populations per patient.
3. `generateQrdaIII` emits the Cat III document; response is a ZIP.

## C1 Document Generation Flow (`createQrdaDocument`)
1. Fetch personal details, insurance cards, appointments via `EHRDataService` (each in its own try/catch, failures → nulls/empties)
2. Build CDA header: realmCode `US`, typeId `2.16.840.1.113883.1.3` / `POCD_HD000040`, 4 templateIds, code `55182-0`
3. Header participants: `recordTarget` → `author` → `custodian` → `legalAuthenticator` → `documentationOf`
4. Body sections: Measure → Reporting Parameters → Patient Data
5. Serialize via `CDAUtil.save()`, then string-patch `xmlns:sdtc` + `sdtc:dischargeDispositionCode`

## Build & Run
```bash
./mvnw spring-boot:run     # needs EHR_* env vars + a reachable PostgreSQL (see compliance-map.md)
./mvnw test                # 41 tests
```

## Known Blockers (detail in [compliance-map.md](compliance-map.md))
- **No schema management** — JPA entities exist but there is no Flyway/Liquibase and no `ddl-auto`; the two tables must be created by hand.
- **No authentication anywhere** — including the admin grant/revoke endpoints.
- **Missing config** — `EHR_API_BASE_URL`, `EHR_TOKEN_URL`, `EHR_CLIENT_AUTH`, `EHR_USERNAME`, `EHR_PASSWORD` have placeholder/empty defaults.
- **C2C3 is untested and off-convention** — no test covers either endpoint, responses bypass the envelope, and the measure identity is hardcoded.
