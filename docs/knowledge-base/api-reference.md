# API Reference

Three controllers, no authentication on any of them.

## Response Envelope
Every endpoint except the two binary QRDA ones returns [ApiResponse](src/main/java/com/onc/api/support/ApiResponse.java), success or failure:

```json
{ "success": true, "code": "ENTITY", "message": null, "data": { }, "path": "/ehr/data/personal-details",
  "requestId": "7f1c…", "version": "1.0", "timestamp": "2026-08-25T09:14:22.117Z" }
```

- `data` on success only; `errors` (field → message) on validation failures only. Both omitted when absent, never `null`.
- `success` is **derived** from `code`, so a body cannot claim success while carrying an error code.
- `code` is a stable string clients branch on — see [ResponseCode](src/main/java/com/onc/api/support/ResponseCode.java); each constant owns its HTTP status, so the mapping exists in one place.
- The one deliberate deviation from "data on success only": a **409 duplicate access request** carries `data.requestId` for the blocking request, because the caller needs it to follow up.

| Code | Status | Raised when |
|---|---|---|
| `OK` · `ENTITY` · `UPDATED` | 200 | read, or a decision applied |
| `CREATED` | 201 | access request filed |
| `BAD_REQUEST` | 400 | unparseable parameter, malformed `fhirId` |
| `VALIDATION_FAILED` | 400 | bean/method validation (no constraints declared yet) |
| `PATIENT_ACCESS_DENIED` | 403 | patient has not been granted access |
| `NOT_FOUND` | 404 | unknown request id, clinic, provider, or route |
| `METHOD_NOT_ALLOWED` | 405 | wrong verb |
| `DUPLICATE_REQUEST` | 409 | an existing request blocks this one |
| `STATUS_TRANSITION_BLOCKED` | 409 | grant/revoke on a request in the wrong status |
| `CONFLICT` | 409 | data-integrity or optimistic-lock failure |
| `UPSTREAM_UNAVAILABLE` | 502 | EHR provider errored or was unreachable |
| `INTERNAL_ERROR` | 500 | anything else — generic message, cause logged only |

## EHR read layer — `/ehr/data`
[EHRDataController.java](src/main/java/com/onc/EHR/controller/EHRDataController.java) → [EHRDataServiceImpl.java](src/main/java/com/onc/EHR/service/impl/EHRDataServiceImpl.java)

| Method | Path | Params | Returns | Auth |
|---|---|---|---|---|
| GET | `/medical-details` | `fhirId` | `MedicalDetailsData` | **none** |
| GET | `/personal-details` | `fhirId` | `PersonalDetailsData` | **none** |
| GET | `/insurance-details` | `fhirId` | `List<InsuranceDetails>` | **none** |
| GET | `/appointment-details` | `fhirId`, `clinicId` (optional) | `AppointmentData` | **none** |
| GET | `/doctor-details` | `doctorId` (int) | `DoctorDetailsData` | **none** |
| GET | `/soap-details` | `fhirId` | `List<FormData>` | **none** |
| GET | `/clinic-details` | `clinicId` (int) | `Clinic` | **none** |
| GET | `/clinics` | `organisationId` (int) | `List<Clinic>` | **none** |
| GET | `/providers` | `clinicId` | `List<DoctorDetailsData>` | **none** |

## QRDA — `/ehr/cms/qrda`
[QRDACMSController.java](src/main/java/com/onc/QRDA/controller/QRDACMSController.java) → `QRDACMSServiceImpl`. The six data endpoints are pass-throughs to `EHRDataService`; only `/file` and `/zip` do QRDA work.

| Method | Path | Params | Returns | Auth |
|---|---|---|---|---|
| GET | `/file` | `fhirId` | `byte[]` QRDA XML, `application/xml` | **none** |
| GET | `/medical-details` · `/personal-details` · `/insurance-details` · `/doctor-details` | as above | delegated | **none** |
| GET | `/soap-details` | `fhirId` (**no `@RequestParam`**) | `List<FormData>` | **none** |
| GET | `/appointment-details` | `fhirId`, `clinicId` (**no annotation**) | `AppointmentData` | **none** |
| POST | `/zip` | body: `List<String>` fhirIds | ZIP `CMS139.zip` | **none** |

**Note:** `fhirId` on `/soap-details` and `clinicId` on `/appointment-details` still lack `@RequestParam` — Spring treats unannotated `String` args as model attributes, so they bind to `null`/empty. The `/ehr/data` equivalents are annotated correctly.

## G2 patient endpoints — `/ehr/g2`
[G2Controller.java](src/main/java/com/onc/G2/controller/G2Controller.java) → `PatientAccessWorkflowService`

| Method | Path | Params | Returns | Auth |
|---|---|---|---|---|
| GET | `/personal-details` | `fhirId` | 200 `PersonalDetailsData`, or **403** `PATIENT_ACCESS_DENIED` | **none** |
| POST | `/request-access` | `fhirId`, `requestType`, `encounterId`, `providerId`, `tinId`, `reportingPeriodStart?`, `reportingPeriodEnd?` | **201** `AccessRequestResult` (`PENDING`), or **409** `DUPLICATE_REQUEST` | **none** |

`requestType` binds through [StringToRequestTypeConverter](src/main/java/com/onc/G2/converter/StringToRequestTypeConverter.java) — still case-insensitive, but an unknown value is now a **400** listing the allowed values rather than a 500. `reportingPeriod*` bind as `LocalDate`, so a malformed date is also a 400 naming the parameter.

## G2 admin endpoints — `/ehr/admin/patient-access`
[PatientAccessAdminController.java](src/main/java/com/onc/G2/controller/PatientAccessAdminController.java)

| Method | Path | Params | Returns |
|---|---|---|---|
| GET | `/pending-requests` · `/access-granted` · `/access-revoked` | `organisationId`, `providerId`, `tinId` | `List<PatientAccessRequestDto>` |
| GET | `/request/{requestId}` | path `Long` | `PatientAccessRequestDto`, **404** when unknown |
| GET | `/patient/{patientFhirId}` | path | `List<PatientAccessRequestDto>` |
| POST | `/grant-access/{requestId}` | path `Long` | `PatientAccessRequestDto` — **404** unknown, **409** wrong status |
| POST | `/revoke-access/{requestId}` | path `Long` | `PatientAccessRequestDto` — **404** unknown, **409** wrong status |
| GET | `/data/tin` | `tinId`, `reportingPeriodStart`, `reportingPeriodEnd` (`yyyy-MM-dd`) | `PatientAccessDataDto` (aggregated) |
| GET | `/data/clinic-provider` | + `providerId` | `PatientAccessDataDto` (aggregated) |
| GET | `/data/all` | period only | `List<PatientAccessDataDto>` |
| GET | `/dashboard/patients-with-access` | `organisationId`, `providerId`, `tinId?`, period | `AccessDashboardResponse` |
| GET | `/dashboard/group-patients-with-access` | `tinId`, period | `AccessDashboardResponse` (with `groupId`) |

All listing and dashboard endpoints are **unpaginated**.

## Error Contract
[GlobalExceptionHandler.java](src/main/java/com/onc/api/GlobalExceptionHandler.java) — one unscoped `@ControllerAdvice` extending `ResponseEntityExceptionHandler`, covering all three modules.

| Exception | Status | Message |
|---|---|---|
| `AppException` | its `ResponseCode` | the exception's message, written to be caller-safe |
| `MethodArgumentNotValidException` · `HandlerMethodValidationException` · `ConstraintViolationException` | 400 | first field error; all of them under `errors` |
| `MissingServletRequestParameterException` | 400 | `Required parameter 'x' is missing.` |
| `TypeMismatchException` | 400 | names the parameter and the expected type; enums list their allowed values |
| `HttpMessageNotReadableException` | 400 | translated — Jackson's own text names Java classes and is never passed through |
| `DataIntegrityViolationException` · `ObjectOptimisticLockingFailureException` | 409 | fixed sentence; the constraint name stays in the log |
| `PropertyReferenceException` | 400 | names the field only, not the entity class |
| anything else | 500 | `Something went wrong. Please try again later.` unless `onc.expose-error-details=true` |

`handleExceptionInternal` is overridden so the base class's RFC 7807 `ProblemDetail` bodies — unknown route, wrong method, unreadable body — are re-shaped into the envelope. [EnvelopeContractTest](src/test/java/com/onc/G2/controller/EnvelopeContractTest.java) guards that.

**Not covered:** 401/403 from a security filter chain. Filters run before the dispatcher servlet, so when Spring Security is added it needs its own `AuthenticationEntryPoint` and `AccessDeniedHandler` writing the same envelope.

## Outbound — EHR Provider API
Auth: OAuth2 password grant via `EHRTokenServiceImpl.getAccessToken()` on the shared `RestTemplate` bean; token fetched **fresh on every call** (no caching). Bearer token on all data calls. All URLs build from `${ehr.api.base-url}`.

| Purpose | URL |
|---|---|
| Token | `${ehr.token.url}` (POST, form-urlencoded: `username`, `password`, `grant_type`; header `Authorization: ${ehr.token.client-auth}`) |
| Medical details | `{base}/medical-details?patient_id=` |
| Personal details | `{base}/personal-details?patient_id=` |
| Insurance cards | `{base}/insurance/cards?patient_id=` |
| Doctor | `{base}/doctor/{doctorId}` |
| Clinic | `{base}/clinic/{clinicId}` |
| Clinics by org | `{base}/branch/organisation/{organisationId}/get-all-clinics` |
| Doctors by clinic | built with `UriComponentsBuilder`, paged by `${ehr.provider-page-size}` |
| SOAP context | `{base}/soap-context?patient_id=` |
| Form data | `{base}/form-data/{submissionId}` |
| Appointments | `UriComponentsBuilder` over `{base}` with `start_date`/`end_date`/`patient_id`/`sort`/`clinic_id` |

`${fhir.base-url}` is injected into `QRDACMSServiceImpl` but never referenced.

## Configuration
| Property | Env override | Default |
|---|---|---|
| `ehr.api.base-url` | `EHR_API_BASE_URL` | `https://provider.staging.ehr/apis/v1` — **placeholder** |
| `ehr.token.url` | `EHR_TOKEN_URL` | `https://provider.staging.ehr/apis/v1/oauth/token` — **placeholder** |
| `ehr.token.client-auth` / `.username` / `.password` | `EHR_CLIENT_AUTH` / `EHR_USERNAME` / `EHR_PASSWORD` | empty — **must be set** |
| `ehr.token.grant-type` | `EHR_GRANT_TYPE` | `password` |
| `ehr.organisation-id` | `EHR_ORGANISATION_ID` | `0` — org whose clinics back `/providers` |
| `ehr.provider-page-size` | `EHR_PROVIDER_PAGE_SIZE` | `50` |
| `spring.datasource.url` / `.username` / `.password` | `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/onc`, `postgres`/`postgres` |
| `qrda.vendor.*`, `qrda.custodian.*`, `qrda.legal-authenticator.*` | `QRDA_*` | placeholder identity written into the QRDA header |
| `onc.expose-error-details` | `ONC_EXPOSE_ERROR_DETAILS` | `false` — **never true in a deployed environment**; it echoes the exception class and message on a 500 |

`.ehr` is not a delegated TLD, so the placeholder hosts do not resolve — a deployment that forgets the env vars fails on connection rather than silently reaching a live host.

## ID Convention
`fhirId` is composite, shape `Organisation-Patient`. Two implementations disagree:
- `EHRDataService.extractPatientId` — returns `null` when there is no `-`.
- `PatientAccessWorkflowServiceImpl.extractPatientId` (private) — returns the **whole input** when there is no `-`.
