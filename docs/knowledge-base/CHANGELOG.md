# KB Changelog

- 2026-08-25 — Adopted a single `ApiResponse` envelope and one `GlobalExceptionHandler` across all three modules. Services return payloads and throw `AppException`; the G2-scoped advice and four duplicated response DTOs are gone. Fixes findings 10, 11, 22, 36, 37. Tests 35 → 41.

- 2026-08-25 — Rebuilt for the `com.onc` three-module layout (EHR / QRDA / G2): new G2 patient-access module (2 entities, 2 repos, 5 services, 14 endpoints, `G2ExceptionHandler`), extracted `EHRDataService`/`EHRTokenService` behind `/ehr/data`, `ConfigurationService` beans, 35 passing tests. Compliance findings 23 → 38.
- 2026-08-21 — Vendor hostnames replaced with `ehr.*` placeholder config; credential defaults removed.
- 2026-08-20 — Initial KB generated; package rewrite from `com.spry.ehr.QRDA.**`.
