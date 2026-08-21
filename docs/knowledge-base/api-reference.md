# API Reference

Base path: `/ehr/spry/cms/qrda` — [QRDACMSSPRYController.java](src/main/java/com/onc/qrdaC1/QRDA/controller/QRDACMSSPRYController.java)

## Inbound Endpoints
| Method | Path | Params | Handler | Returns | Auth |
|---|---|---|---|---|---|
| GET | `/file` | `fhirId` (query, required) | `getQrda` → `QRDACMSSPRYImpl:51` | `byte[]` QRDA XML, `application/xml` | **none** |
| GET | `/medical-details` | `fhirId` (query, required) | `fetchSpryPatientMedicalDetails:63` | `MedicalDetailsData` | **none** |
| GET | `/personal-details` | `fhirId` (query, required) | `fetchSpryPatientPersonalDetails:94` | `PersonalDetailsData` | **none** |
| GET | `/soap-details` | `fhirId` (**no `@RequestParam`** — see note) | `fetchSprySoapDetails:246` | `List<FormData>` | **none** |
| GET | `/insurance-details` | `fhirId` (query, required) | `fetchSpryPatientInsuranceDetails:185` | `List<InsuranceDetails>` | **none** |
| GET | `/appointment-details` | `fhirId` (required), `clinicId` (**no annotation**) | `fetchSpryAppointments:328` | `AppointmentData` | **none** |
| GET | `/doctor-details` | `doctorId` (query, int, required) | `fetchSpryDoctorDetails:219` | `DoctorDetailsData` | **none** |
| POST | `/zip` | body: `List<String>` fhirIds | `generateQrdaZip:414` | ZIP `CMS139.zip`, `application/octet-stream` | **none** |

**Note:** `fhirId` on `/soap-details` and `clinicId` on `/appointment-details` lack `@RequestParam`. Spring MVC treats unannotated `String` args as model attributes, not query params — these bind to `null`/empty, not the query string.

## Response Codes
| Condition | Status |
|---|---|
| `extractPatientId` returns null (no `-` in fhirId) | 400 |
| Upstream non-2xx | mirrors upstream status, empty body |
| Empty insurance / empty SOAP assessments | 204 |
| Any exception | 500, empty body (exception swallowed, mostly unlogged) |

## Outbound — EHR Provider API
Auth: OAuth2 password grant via `QRDATokenServiceImpl.getAccessToken()`; token fetched **fresh on every call** (no caching). Bearer token on all data calls.

All 8 calls build from the single injected `apiBaseUrl` (`${ehr.api.base-url}`) — source [QRDACMSServiceImpl.java](src/main/java/com/onc/qrdaC1/QRDA/service/impl/QRDACMSServiceImpl.java).

| Purpose | URL | Line |
|---|---|---|
| Token | `${ehr.token.url}` (POST, form-urlencoded: `username`, `password`, `grant_type`; header `Authorization: ${ehr.token.client-auth}`) | `QRDATokenServiceImpl:30` |
| Medical details | `{apiBaseUrl}/medical-details?patient_id=` | `:86` |
| Personal details | `{apiBaseUrl}/personal-details?patient_id=` | `:118` |
| Form data | `{apiBaseUrl}/form-data/{submissionId}` | `:179`, `:292` |
| Insurance cards | `{apiBaseUrl}/insurance/cards?patient_id=` | `:208` |
| Doctor | `{apiBaseUrl}/doctor/{doctorId}` | `:237` |
| SOAP context | `{apiBaseUrl}/soap-context?patient_id=` | `:270` |
| Appointments | `{apiBaseUrl}/appointment?start_date=2023-01-01T00:00:00.000Z&end_date=2025-12-31T00:00:00.000Z&patient_id=&sort=asc&clinic_id=` | `:351` |

`${fhir.base-url}` is injected as `baseUrl` but never referenced.

### Configuration (vendor-neutral as of 2026-08-21)
| Property | Env override | Default |
|---|---|---|
| `ehr.api.base-url` | `EHR_API_BASE_URL` | `https://provider.staging.ehr/apis/v1` — **placeholder, must be set** |
| `ehr.token.url` | `EHR_TOKEN_URL` | `https://provider.staging.ehr/apis/v1/oauth/token` — **placeholder, must be set** |
| `ehr.token.client-auth` | `EHR_CLIENT_AUTH` | empty — **must be set** |
| `ehr.token.username` / `.password` | `EHR_USERNAME` / `EHR_PASSWORD` | empty — **must be set** |
| `ehr.token.grant-type` | `EHR_GRANT_TYPE` | `password` |

`.ehr` is not a delegated TLD, so the placeholder hosts do not resolve — a deployment that forgets to set the environment variables fails on connection rather than silently reaching a live host.

## ID Convention
`extractPatientId(fhirId)` (`:369`) splits on `-` and returns `parts[1]`. Input shape expected: `Patient-12345`. Any id without `-`, or with `-` in a leading segment, yields the wrong id or `null`.
