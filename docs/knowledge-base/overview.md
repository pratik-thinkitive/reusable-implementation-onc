# Overview — qrdaC1

## Purpose
Spring Boot REST service that generates **QRDA Category I** (Quality Reporting Document Architecture) XML for CMS eCQM **CMS139** (Falls: Screening for Future Fall Risk, patients ≥65). Pulls clinical data from the Spry Health provider API and emits HL7 CDA R2 documents, singly or as a ZIP batch.

## Tech Stack
| Layer | Choice | Version |
|---|---|---|
| Language | Java | 17 |
| Framework | Spring Boot (`spring-boot-starter-webmvc`) | 4.1.0 |
| Build | Maven (wrapper `mvnw`) | — |
| CDA generation | `org.openehealth.ipf.oht.mdht:ipf-oht-mdht-uml-cda` (MDHT) | 1.2.0.201212201425 |
| FHIR | `ca.uhn.hapi.fhir:hapi-fhir-structures-r4` | 6.4.0 (declared, unused in code) |
| Persistence | `spring-boot-starter-data-jpa` + PostgreSQL | declared, **no entities/repos exist** |
| Boilerplate | Lombok (`@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j`) | — |
| Validation | `spring-boot-starter-validation` | declared, **no constraints used** |
| Test | `*-test` starters, JUnit 5 | 1 test (`contextLoads`) |

## Architecture
```
HTTP  →  QRDACMSSPRYController  (/ehr/spry/cms/qrda)
             │
             ▼
         QRDACMSSPRY (interface) ──► QRDACMSSPRYImpl (2020 LOC, all logic)
             │                            │
             │                            ├─► QRDASpryTokenService(Impl)  → OAuth2 password grant
             │                            ├─► RestTemplate → Spry provider API (staging, hardcoded)
             │                            └─► MDHT CDAFactory → ClinicalDocument → CDAUtil.save() → XML
             ▼
         57 DTOs (com.spry.ehr.QRDA.dto) — Jackson-mapped Spry API payloads
```

## Module Map
| Path | Contents |
|---|---|
| [QrdaC1Application.java](src/main/java/com/onc/qrdaC1/QrdaC1Application.java) | Spring Boot entrypoint (only class in `com.onc.qrdaC1`) |
| [QRDA/controller/](src/main/java/com/onc/qrdaC1/QRDA/controller/) | 1 REST controller, 8 endpoints |
| [QRDA/service/](src/main/java/com/onc/qrdaC1/QRDA/service/) | 2 interfaces: `QRDACMSSPRY`, `QRDASpryTokenService` |
| [QRDA/service/impl/](src/main/java/com/onc/qrdaC1/QRDA/service/impl/) | `QRDACMSSPRYImpl` (2020 LOC), `QRDASpryTokenServiceImpl` (56 LOC) |
| [QRDA/dto/](src/main/java/com/onc/qrdaC1/QRDA/dto/) | 57 DTOs + `LoincCodesDeserializer` |

## Document Generation Flow (`createQrdaDocument`)
1. Fetch personal details, insurance cards, appointments from Spry (each in its own try/catch, failures → nulls/empties)
2. Build CDA header: realmCode `US`, typeId `2.16.840.1.113883.1.3` / `POCD_HD000040`, 4 templateIds, code `55182-0` (LOINC, Quality Measure Report)
3. Header participants: `recordTarget` → `author` → `custodian` → `legalAuthenticator` → `documentationOf`
4. Body sections: Measure → Reporting Parameters → Patient Data
5. Serialize via `CDAUtil.save()`, then string-patch `xmlns:sdtc` + `sdtc:dischargeDispositionCode` (`generateXmlWithValueSetFix`)

## Build & Run
```bash
./mvnw spring-boot:run     # requires fhir.base-url + spry.token.* properties (see compliance-map.md)
./mvnw test
```

## Known Blockers (detail in [compliance-map.md](compliance-map.md))
- ~~**Package/directory mismatch**~~ — *resolved 2026-08-20*: all 62 files rewritten from `com.spry.ehr.QRDA.**` to `com.onc.qrdaC1.QRDA.**`; `./mvnw compile` succeeds.
- **Missing config** — `fhir.base-url`, `spry.token.{url,base-url,client-auth,username,password,grant-type}` are not in [application.yaml](src/main/resources/application.yaml); context fails to start.
- **Missing beans** — `RestTemplate` and `ObjectMapper` are constructor-injected but no `@Configuration`/`@Bean` exists.
