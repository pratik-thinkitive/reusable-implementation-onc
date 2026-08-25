# Patterns & Conventions

## Layering
- `@RestController` → interface → `@Service` impl, always.
- Controllers extend [BaseController](src/main/java/com/onc/api/support/BaseController.java) and return `ResponseEntity<ApiResponse<T>>` built by its `data(...)` / `success(...)` helpers. They never construct a `ResponseEntity` by hand and never `try/catch` to build a response — that is the global handler's job.
- Services return the payload itself and signal failure by throwing `AppException`. Nothing below the controller touches `ResponseEntity`, `HttpStatus`, or `ApiResponse`. The two binary QRDA endpoints are the exception: XML and a ZIP cannot ride inside the envelope, so those two service methods still return `ResponseEntity`.
- Constructor injection via Lombok `@RequiredArgsConstructor` on `private final` fields.
- Config via field-level `@Value("${...}")` — no `@ConfigurationProperties`.
- Shared infrastructure beans live in [config/ConfigurationService.java](src/main/java/com/onc/config/ConfigurationService.java): one `RestTemplate` and one lenient `ObjectMapper` (`FAIL_ON_UNKNOWN_PROPERTIES=false`, `ACCEPT_SINGLE_VALUE_AS_ARRAY=true`).
- Cross-module direction is one-way: **G2 → EHR** and **QRDA → EHR**. EHR never depends on either.

## HTTP Client Pattern (EHR module)
`EHRDataServiceImpl` funnels every outbound read through two private helpers — one performs the call and fails unless it came back 2xx with a body, the other adds deserialization:
```java
private String get(String url, String what)                    // throws AppException(UPSTREAM_UNAVAILABLE)
private <T> T get(String url, String what, Class<T> type)      // + ObjectMapper.readValue
```
Responses are fetched as `String` and deserialized with the injected `ObjectMapper`, never as a typed `exchange(..., Dto.class)`. The upstream status and body never reach the client: the body carries PHI, and the status describes the provider rather than the caller's request. Newer call sites (`fetchAppointments`, `fetchAllDoctorsByClinicId`) build URLs with `UriComponentsBuilder`; the older ones still concatenate.

A read that finds nothing returns an empty list, or throws `NOT_FOUND` where a single resource was addressed. The 204s the previous signature produced could not survive an envelope that always carries a body.

## Error Handling
One convention across all three modules: **throw, never convert**.
- Business rejections throw `AppException` carrying a `ResponseCode` and a message written to be shown to the caller — no stack traces, SQL, exception class names, or upstream hostnames.
- [GlobalExceptionHandler](src/main/java/com/onc/api/GlobalExceptionHandler.java) is the only place that turns a failure into a response. It extends `ResponseEntityExceptionHandler` and overrides `handleExceptionInternal`, without which the base class's `ProblemDetail` bodies would ship a different shape for unknown routes, wrong methods, and unreadable bodies.
- `onc.expose-error-details` (default `false`) gates whether a 500 names its cause. The default is asserted in a test so it stays a real guarantee.
- The remaining `try/catch` blocks exist to *degrade*, not to convert, and each says why: `PatientAttributionServiceImpl` (an access request must be fileable when the directory is down), `QRDACMSServiceImpl` (a missing section is omitted rather than failing the document — which is why the result can be clinically incomplete without signalling it), and `EHRDataServiceImpl`'s per-item fan-outs (one bad form or clinic must not empty the whole read).

## Transaction Boundaries
- `PatientAccessRequestServiceImpl` and `PatientAccessDataServiceImpl` are class-level `@Transactional`.
- `PatientAccessAdminServiceImpl.grantAccess` / `revokeAccess` are `@Transactional` so status and counters cannot disagree.
- `PatientAccessWorkflowServiceImpl` is **deliberately not** transactional — it calls the EHR over HTTP and would pin a connection for the round trip.

## Null-Safety Idiom
`Objects.nonNull(x)`, `StringUtils.hasText(s)`, `CollectionUtils.isEmpty(c)` before dereference. Keyed maps unwrap as `map.values().stream().filter(Objects::nonNull).findFirst().orElse(null)`. Lookups that can fail return an empty object (`new PatientAttribution()`) rather than throwing.

## Dates
- G2 persists `Instant` for timestamps and `LocalDate` for period bounds.
- `PatientAccessDataServiceImpl` pins `REPORTING_ZONE = ZoneOffset.UTC` when deciding which calendar day an `Instant` falls on.
- `ReportingPeriod.currentCalendarYear()` uses bare `LocalDate.now()` — **system default zone**, inconsistent with the above.
- QRDA still formats HL7 `yyyyMMddHHmmss` through `ZoneId.systemDefault()` and `SimpleDateFormat`.

## Comment Style
Short, reason-focused; Javadoc on interfaces and non-obvious classes explains *why* (why not transactional, why a zone is pinned, why an enum rename needs a migration), not *what*.

## CDA Construction (MDHT)
- Built imperatively with `CDAFactory.eINSTANCE.createXxx()`; one private `addXxx(...)` per header participant and body section.
- Ids: `UUID.randomUUID()` per element, root `1.3.6.1.4.1.115`. **Non-deterministic** — the same patient yields a different document id each call; `createII` argument order is inverted at one site.
- Serialization: `CDAUtil.save()` → **string/regex post-processing** to inject `xmlns:sdtc` and `sdtc:dischargeDispositionCode`. Always call `generateXmlWithValueSetFix`, never `generateXml`.
- Vendor identity (`manufacturerModelName`, custodian, legal authenticator) comes from `qrda.*` properties, not literals.

## Terminology Mapping Idiom
Code-system dispatch by literal string equality on prefixed codes (`"SCT-32485007".equals(...)` → inpatient encounter, `"SCT-183919006"` → hospice). Race/ethnicity/gender map through `if/else-if` chains on lowercased free text with a `"UN"`/`""` fallback — adding a value means editing the chain.

## Healthcare-Specific Conventions
- **QRDA document**: Cat I, US realm, `typeId 2.16.840.1.113883.1.3 / POCD_HD000040`, code `55182-0` LOINC.
- **Template ids**: `…22.1.1`→2015-08-01 · `…24.1.1`→2017-08-01 · `…24.1.2`→2021-08-01 · `…24.1.3`→2022-02-01.
- **Sections**: Measure `…24.2.2`/`…24.2.3` code `55186-1` · Reporting Parameters `…17.2.1` code `55187-9` · Patient Data `…17.2.4`/`…24.2.1` code `55188-7`.
- **Code systems**: LOINC `…6.1` · SNOMED CT `…6.96` · CPT `…6.12` · CDCREC `…6.238` · AdministrativeGender `…5.1` · Confidentiality `…5.25`.
- **CMS139**: HQMF id `8A6D0454-8DF0-2D9F-018E-1434289012A6`; initial population = age ≥65 at period start **and** ≥1 appointment in the period.
- **G2 measure**: denominator = patient had an in-period encounter (set on request or grant); numerator = access granted and the patient viewed their data. Both are 0-or-1 per patient per period; `isNumeratorRecorded` stays true after revocation so revoked patients still appear on dashboards.

## Testing
MockMvc slice tests over the G2 controllers with mocked services — 5 classes, 41 tests, all passing. `@DisplayName` describes behaviour in prose. [EnvelopeContractTest](src/test/java/com/onc/G2/controller/EnvelopeContractTest.java) pins the envelope itself rather than any one endpoint: the `ProblemDetail` guard, the validation messages, and that a 500 never echoes its cause. No tests for the EHR or QRDA modules: CDA generation, terminology mapping, date conversion, and `isInInitialPopulation` are uncovered.
