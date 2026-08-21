# Patterns & Conventions

## Layering
- `@RestController` → interface (`QRDACMSSPRY`) → `@Service` impl. Controller is a pass-through; it returns the service's `ResponseEntity` unchanged and holds no logic.
- Constructor injection via Lombok `@RequiredArgsConstructor` on `private final` fields (exception: controller declares `public final QRDACMSSPRY`).
- Config values via field-level `@Value("${...}")` — no `@ConfigurationProperties`.

## HTTP Client Pattern (repeated 8×, verbatim)
```java
String patientId = extractPatientId(fhirId);
if (patientId == null) return ResponseEntity.badRequest().body(null);
String token = spryTokenService.getAccessToken();          // fresh token every call, no cache
HttpHeaders h = new HttpHeaders();
h.setBearerAuth(token);
h.setAccept(List.of(MediaType.APPLICATION_JSON));
ResponseEntity<String> r = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(h), String.class);
if (!r.getStatusCode().is2xxSuccessful()) return ResponseEntity.status(r.getStatusCode()).body(null);
XxxResponse dto = objectMapper.readValue(r.getBody(), XxxResponse.class);
return ResponseEntity.ok(dto.getData());
```
Responses are always fetched as `String` and manually deserialized with `ObjectMapper`, never as a typed `exchange(..., Dto.class)`.

## Error Handling
- Every public method wraps its body in `try { ... } catch (Exception e) { return 500; }`. No `@ControllerAdvice`, no `@ExceptionHandler`, no custom exception types.
- Several catch blocks are **empty** (`createQrdaDocument:463,472,482`; `getPersonalDetailsData:394`; `getAppointments:408`; `parseLoincCodes:1989`; `parseSnomedCodes:2013`) — upstream failures degrade silently into an emptier QRDA document rather than an error.
- Mixed output channels: `log.error` (2 sites), `System.err.println` (`:363`), `e.printStackTrace()` (`:1863`, `:1892`).

## Null-Safety Idiom
`Objects.nonNull(x)`, `StringUtils.hasText(s)`, `CollectionUtils.isEmpty(c)` used consistently before every dereference. Keyed maps are unwrapped as `map.values().stream().filter(Objects::nonNull).findFirst().orElse(null)`.

## CDA Construction (MDHT)
- Everything is built imperatively with `CDAFactory.eINSTANCE.createXxx()` / `DatatypesFactory.eINSTANCE.createXxx()`; one private `addXxx(...)` method per header participant and per body section.
- Ids: `UUID.randomUUID()` per element, root `1.3.6.1.4.1.115` (Spry OID) with the UUID as extension. **Non-deterministic — the same patient yields a different document id on every call**, and `createII` argument order is inverted at `:1137` (root/extension swapped) relative to the rest of the file.
- Serialization: `CDAUtil.save()` → then **string/regex post-processing** to inject `xmlns:sdtc` and `sdtc:dischargeDispositionCode` (`generateXml:1852`, `addSdtcValueSetForTargetCodeSystem:1868`). Always call `generateXmlWithValueSetFix`, never `generateXml` directly.
- Dates: HL7 `yyyyMMddHHmmss`. `convertToReqdFormatDate` (ISO-8601 → HL7), `convertEpochToStandardTime` (epoch seconds → HL7), `getCurrentTimestamp` (now + offset). All resolve through `ZoneId.systemDefault()` — output varies with server timezone.

## Terminology Mapping Idiom
Code-system dispatch is done with literal string equality on prefixed codes:
```java
if ("SCT-32485007".equals(snomedCode.getCode()))  → createInpatientEncounterEntry(...)
else if ("SCT-183919006".equals(snomedCode.getCode())) → createHospiceEncounterEntry(...)
```
Race/ethnicity/gender map through `if/else-if` chains on lowercased free text with a fallback of `"UN"` or `""`. Adding a value means editing the chain.

## Healthcare-Specific Conventions
- **Document**: QRDA Cat I, US realm, `typeId 2.16.840.1.113883.1.3 / POCD_HD000040`, code `55182-0` LOINC.
- **Template ids** (root → extension): `…22.1.1`→2015-08-01 (US Realm Header) · `…24.1.1`→2017-08-01 (QRDA) · `…24.1.2`→2021-08-01 (QDM-based QRDA) · `…24.1.3`→2022-02-01 (CMS QRDA).
- **Sections**: Measure `…24.2.2` + `…24.2.3`, code `55186-1` · Reporting Parameters `…17.2.1` + `…17.2.1.1`, code `55187-9` · Patient Data `…17.2.4` + `…24.2.1` + `…24.2.1.1`, code `55188-7`.
- **Entries**: Encounter Performed `…22.4.49` + `…24.3.23` · Measure Reference `…24.3.98`/`…24.3.97` · Reporting Parameters Act `…17.3.8`/`…17.3.8.1` · Patient Characteristic Payer `…24.3.55` (LOINC `48768-6`) · Assessment `…24.3.155`.
- **Code systems in use**: LOINC `2.16.840.1.113883.6.1` · SNOMED CT `2.16.840.1.113883.6.96` · CPT `2.16.840.1.113883.6.12` · CDCREC race/ethnicity `2.16.840.1.113883.6.238` · AdministrativeGender `2.16.840.1.113883.5.1` · Confidentiality `2.16.840.1.113883.5.25`.
- **CPT derivation**: appointment category `"Initial Evaluation"` → `99203`, `"Follow-up"` → `99213`, default `99213`.
- **Measure**: CMS139, HQMF id `8A6D0454-8DF0-2D9F-018E-1434289012A6`. Initial population = age ≥65 at period start **and** ≥1 appointment within the period (`isInInitialPopulation:1912`).

## Testing
Single test: `QrdaC1ApplicationTests.contextLoads`. No unit tests for CDA generation, terminology mapping, date conversion, or population logic.
