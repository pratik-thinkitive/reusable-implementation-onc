# Compliance & Security Map

**Domain: healthcare / PHI.** The service fetches and emits patient demographics, encounters, insurance coverage, diagnoses, medications, and clinical assessments. HIPAA Security Rule safeguards apply to every endpoint below.

## Build / Startup Blockers
| # | Severity | Location | Issue | Fix |
|---|---|---|---|---|
| 1 | ~~Critical~~ **FIXED** | all of `src/main/java/com/onc/qrdaC1/QRDA/**` | Imported code carried its source project's packages: directory path was `com/onc/qrdaC1/QRDA/…` while every file declared `package com.spry.ehr.QRDA.…`. | **Resolved 2026-08-20** — 71 references across 62 files rewritten to `com.onc.qrdaC1.QRDA.…` (packages, imports, and 2 fully-qualified usages at `QRDACMSSPRYImpl:116`). `./mvnw compile` passes, 66 classes emitted. |
| 2 | Critical | [application.yaml](src/main/resources/application.yaml) | `fhir.base-url`, `spry.token.url`, `spry.token.base-url`, `spry.token.client-auth`, `spry.token.username`, `spry.token.password`, `spry.token.grant-type` are all referenced by `@Value` with no defaults and absent from config → context fails to start | Add the keys with `${ENV_VAR}` indirection; no literal secrets in the file |
| 3 | Critical | `QRDACMSSPRYImpl:45-46` | `RestTemplate` and `ObjectMapper` are constructor-injected but no `@Configuration`/`@Bean` defines them; Boot auto-configures `RestTemplateBuilder`, not `RestTemplate` | Add a `@Configuration` class exposing both beans (with timeouts — see #8) |
| 4 | High | `QrdaC1Application` + pom | `spring-boot-starter-data-jpa` + PostgreSQL are on the classpath with zero entities/repositories; a datasource is still auto-configured and pointed at `localhost:5432/onc` | Remove both dependencies, or exclude `DataSourceAutoConfiguration` |

## PHI Exposure & HIPAA Safeguards
| # | Severity | Location | Issue | Fix |
|---|---|---|---|---|
| 5 | Critical | [QRDACMSSPRYController.java](src/main/java/com/onc/qrdaC1/QRDA/controller/QRDACMSSPRYController.java) — all 8 endpoints | **No authentication or authorization anywhere.** Spring Security is not a dependency. Any caller who can reach the port retrieves full PHI for an arbitrary `fhirId`, or bulk-exports it via `POST /zip`. Violates HIPAA §164.312(a) access control. | Add `spring-boot-starter-security` + OAuth2 resource-server; enforce per-patient authorization, not just authentication |
| 6 | Critical | whole service | **No audit logging.** HIPAA §164.312(b) requires recording who accessed which patient's record and when. No access log, no user identity, no correlation id. | Emit a structured audit event (actor, patientId, endpoint, timestamp, outcome) on every PHI read |
| 7 | High | `QRDACMSSPRYImpl:155`, `:322` | `log.error("… Response: {}", spryResponse.getBody(), e)` writes the **entire upstream PHI payload** into application logs on failure | Log status + a correlation id only; never the body |
| 8 | High | `QRDATokenServiceImpl:18-25` | Client credentials (`client-auth`, `username`, `password`) held as plain `@Value` strings and sent on every request; a new token is minted per API call (no cache, no expiry handling). *Partly mitigated 2026-08-21 — the committed credential defaults were removed; the env vars are now the only source.* | Source from a secret manager; cache the token until `expires_in` |
| 9 | ~~High~~ **FIXED** | `QRDACMSServiceImpl` | Six upstream URLs were hardcoded to `https://provider.staging.spryhealth.care`, so a production deployment would silently read staging data. | **Resolved 2026-08-21** — all 8 calls now build from `${ehr.api.base-url}`; vendor hostnames removed from the repo and replaced with non-resolving `provider.staging.ehr` placeholders. Deployments must set `EHR_API_BASE_URL` / `EHR_TOKEN_URL`. |
| 10 | High | `QRDACMSSPRYImpl:45`, `QRDASpryTokenServiceImpl:31` | `RestTemplate` used with no connect/read timeout and no retry; a hung upstream blocks a servlet thread indefinitely. `getAccessToken` also constructs a fresh `RestTemplate` per call. | Configure timeouts on the shared bean; inject it into the token service |
| 11 | Medium | `QRDACMSSPRYImpl:369` `extractPatientId` | Unvalidated `fhirId` split on `-`, `parts[1]` returned and concatenated straight into upstream URLs — no encoding, no format check | Validate against an expected pattern; use `UriComponentsBuilder` for every URL |
| 12 | Medium | `generateQrdaZip:414` | Unbounded `List<String>` request body; each id triggers 4+ upstream calls and the whole ZIP is buffered in memory (`ByteArrayOutputStream`). No size cap, no rate limit. | Cap the list size, stream the response |

## Correctness / Data Integrity
| # | Severity | Location | Issue | Fix |
|---|---|---|---|---|
| 13 | High | `generateQrdaZip:420-421` vs `addReportingParametersSection:1085,1101-1103` | Measurement period is **hardcoded twice and inconsistently**: eligibility uses `2023-01-01`→`2025-12-31`, while the Reporting Parameters section declares `20230101`→`20231231`. The reported period disagrees with the population filter — CMS submissions will not validate. | Single configurable measurement period; a QRDA period must be one reporting year |
| 14 | High | `generateQrdaZip:440` | ZIP entry name is `firstName + "_" + lastName + ".xml"` — yields literal `null_null.xml` when names are missing, collides for duplicate names (silently overwriting an entry), and **puts patient names in filenames** | Name entries by an opaque id; guard against collisions |
| 15 | High | `createQrdaDocument:463,472,482` + `getPersonalDetailsData:394`, `getAppointments:408` | Empty catch blocks: if personal details / insurance / appointments fail to load, generation proceeds and emits a structurally valid but **clinically incomplete** QRDA with no signal to the caller | Fail the request when required data is unavailable |
| 16 | Medium | `createQrdaDocument:515`, `addMeasureSection:1035,1050` | `UUID.randomUUID()` for document id, measure set id, and entry ids — the same patient produces a **different document identity on every call**, defeating de-duplication on resubmission | Derive stable ids from patient + measure + period |
| 17 | Medium | `addPatientDataSection:1137` | `createII("1.3.6.1.4.1.115", encounterUUID)` — root/extension are **swapped** relative to every other id in the file (`createII(uuid, "1.3.6.1.4.1.115")`) | Match the convention used elsewhere |
| 18 | Medium | `:1839`, `:1904`, `:1848`, `isInInitialPopulation:1942` | All date handling resolves through `ZoneId.systemDefault()` / default-locale `SimpleDateFormat`; emitted HL7 timestamps and the age-≥65 determination shift with server timezone | Pin an explicit zone; `SimpleDateFormat` is not thread-safe — use `DateTimeFormatter` |
| 19 | Medium | `addSdtcValueSetForTargetCodeSystem:1868` | Conformance is patched by **regex over serialized XML** to insert `sdtc:dischargeDispositionCode`; a whitespace or attribute-order change from MDHT silently drops the fix (the fallback pattern already exists because the first one failed) | Set the element through the MDHT model, or a DOM transform |
| 20 | Medium | `QRDACMSSPRYController:35,45` | `fhirId` on `/soap-details` and `clinicId` on `/appointment-details` are missing `@RequestParam`, so query values never bind | Add the annotations |
| 21 | Low | `QRDACMSSPRYImpl:1220-1266` (commented block) | ~15 lines of commented-out SNOMED assessment handling left inline; duplicated live below | Delete |
| 22 | Low | [data-models.md](data-models.md) — `MedicalDetails` | `@JsonProperty` keys carry upstream typos and stray whitespace (`"Clinican Test…"`, `"Heath Concern"`, `"Diagnostic Imaging Report "`); silent mismatch risk if upstream corrects them | Pin with tests |
| 23 | Low | `QRDACMSSPRYController:17` | `public final QRDACMSSPRY qrdacmsspry` — public mutable-visibility field | `private final` |

## Test Coverage
Single test (`contextLoads`) — and it cannot pass while #1–#3 stand. Zero coverage of CDA generation, `isInInitialPopulation`, terminology mapping, date conversion, or the regex XML patch. For a CMS submission artifact, generated documents should be validated against the CMS QRDA Cat I schematron in CI.

## Not Applicable / Not Found
- No PCI-DSS scope (no payment data). Insurance payer/plan data is PHI, not cardholder data.
- No consent-management or break-glass logic anywhere in the codebase.
- No encryption-at-rest concern in-process (nothing is persisted); transport is HTTPS to upstream, but the service's own TLS/ingress config is outside this repo.
