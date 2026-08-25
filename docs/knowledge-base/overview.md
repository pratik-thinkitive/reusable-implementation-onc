# Overview — ONC (reusable-implementation-onc)

## Purpose
Spring Boot REST service implementing two ONC/CMS reporting concerns over a shared, vendor-neutral EHR read layer:
- **QRDA** — generates **QRDA Category I** XML for eCQM **CMS139** (Falls: Screening for Future Fall Risk, ≥65), singly or as a ZIP batch.
- **G2** — patient-access measure ("Provide Patients Electronic Access"): patients request access to their own health information, admins grant/revoke, and the service keeps numerator/denominator per patient per reporting period.

## Tech Stack
| Layer | Choice | Version |
|---|---|---|
| Language | Java | 17 |
| Framework | Spring Boot (`spring-boot-starter-webmvc`) | 4.1.0 |
| Build | Maven (wrapper `mvnw`), artifact `com.onc:qrdaC1` | — |
| CDA generation | `org.openehealth.ipf.oht.mdht:ipf-oht-mdht-uml-cda` (MDHT) | 1.2.0.201212201425 |
| FHIR | `ca.uhn.hapi.fhir:hapi-fhir-structures-r4` | 6.4.0 (declared, unused in code) |
| Persistence | `spring-boot-starter-data-jpa` + PostgreSQL | **in use** — 2 entities, 2 repositories (G2 only) |
| Boilerplate | Lombok (`@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j`) | — |
| Validation | `spring-boot-starter-validation` | declared, **no constraints used** |
| Test | `*-test` starters, JUnit 5, MockMvc | 5 classes / 41 tests, all passing |

## Architecture
```
                    ┌──────────────────────────────────────────────┐
HTTP /ehr/cms/qrda  │ QRDACMSController → QRDACMSService(Impl)      │  CDA/MDHT → QRDA Cat I XML
                    └──────────────┬───────────────────────────────┘
                                   │ EHR reads delegated
HTTP /ehr/data      ┌──────────────▼───────────────────────────────┐
                    │ EHRDataController → EHRDataService(Impl)      │──► RestTemplate ──► EHR provider API
                    │                     EHRTokenService(Impl)     │──► OAuth2 password grant
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

All three controllers extend BaseController and answer with the ApiResponse
envelope; all three throw AppException, which one GlobalExceptionHandler turns
into that same envelope. The QRDA /file and /zip endpoints are the exceptions —
they return XML and a ZIP, and only their failures are enveloped.
```

## Module Map
| Path | Contents |
|---|---|
| [ONCApplication.java](src/main/java/com/onc/ONCApplication.java) | Spring Boot entrypoint |
| [config/ConfigurationService.java](src/main/java/com/onc/config/ConfigurationService.java) | `RestTemplate` + `ObjectMapper` beans (lenient: unknown props ignored, single-value-as-array) |
| [api/support/](src/main/java/com/onc/api/support/) | `ApiResponse` envelope, `ResponseCode` (each constant owns its HTTP status), `BaseController` |
| [api/GlobalExceptionHandler.java](src/main/java/com/onc/api/GlobalExceptionHandler.java) | The one place a failure becomes a response, for all three modules |
| [common/](src/main/java/com/onc/common/) | `AppException` (unchecked, carries a `ResponseCode`), `AppService.throwError` |
| [EHR/controller/](src/main/java/com/onc/EHR/controller/) | `EHRDataController` — 9 endpoints under `/ehr/data` |
| [EHR/service/](src/main/java/com/onc/EHR/service/) | `EHRDataService` (384 LOC impl), `EHRTokenService` (shared `RestTemplate`) |
| [EHR/dto/](src/main/java/com/onc/EHR/dto/) | 62 DTOs + `LoincCodesDeserializer` — Jackson-mapped provider payloads |
| [QRDA/controller/](src/main/java/com/onc/QRDA/controller/) | `QRDACMSController` — 8 endpoints under `/ehr/cms/qrda` |
| [QRDA/service/impl/](src/main/java/com/onc/QRDA/service/impl/) | `QRDACMSServiceImpl` (1770 LOC) — CDA build; EHR reads are pass-throughs |
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

## Document Generation Flow (`createQrdaDocument`)
1. Fetch personal details, insurance cards, appointments via `EHRDataService` (each in its own try/catch, failures → nulls/empties)
2. Build CDA header: realmCode `US`, typeId `2.16.840.1.113883.1.3` / `POCD_HD000040`, 4 templateIds, code `55182-0`
3. Header participants: `recordTarget` → `author` → `custodian` → `legalAuthenticator` → `documentationOf`
4. Body sections: Measure → Reporting Parameters → Patient Data
5. Serialize via `CDAUtil.save()`, then string-patch `xmlns:sdtc` + `sdtc:dischargeDispositionCode`

## Build & Run
```bash
./mvnw spring-boot:run     # needs EHR_* env vars + a reachable PostgreSQL (see compliance-map.md)
./mvnw test                # 35 tests
```

## Known Blockers (detail in [compliance-map.md](compliance-map.md))
- **No schema management** — JPA entities exist but there is no Flyway/Liquibase and no `ddl-auto`; the two tables must be created by hand.
- **No authentication anywhere** — including the admin grant/revoke endpoints.
- **Missing config** — `EHR_API_BASE_URL`, `EHR_TOKEN_URL`, `EHR_CLIENT_AUTH`, `EHR_USERNAME`, `EHR_PASSWORD` have placeholder/empty defaults.
